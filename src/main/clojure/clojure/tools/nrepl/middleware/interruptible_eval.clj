(ns ^{:author "Chas Emerick"}
     clojure.tools.nrepl.middleware.interruptible-eval
  (:require [clojure.tools.nrepl.transport :as t]
            clojure.main)
  (:use [clojure.tools.nrepl.misc :only (response-for returning)])
  (:import clojure.lang.LineNumberingPushbackReader
           (java.io StringReader Writer)
           java.util.concurrent.atomic.AtomicLong
           (java.util.concurrent ArrayBlockingQueue LinkedBlockingQueue
                                 TimeUnit ThreadPoolExecutor
                                 ThreadFactory)))

(def ^{:dynamic true
       :doc "The message currently being evaluated."}
      *msg* nil)

(defn evaluate
  "Evaluates some code within the dynamic context defined by a map of `bindings`,
   as per `clojure.core/get-thread-bindings`.

   Uses `clojure.main/repl` to drive the evaluation of :code in a second
   map argument (either a string or a seq of forms to be evaluated), which may
   also optionally specify a :ns (resolved via `find-ns`).  The map MUST
   contain a Transport implementation in :transport; expression results and errors
   will be sent via that Transport.

   Returns the dynamic scope that remains after evaluating all expressions
   in :code.

   It is assumed that `bindings` already contains useful/appropriate entries
   for all vars indicated by `clojure.main/with-bindings`."
  [bindings {:keys [code ns transport] :as msg}]
  (let [bindings (atom (merge bindings (when ns {#'*ns* (-> ns symbol find-ns)})))
        out (@bindings #'*out*)
        err (@bindings #'*err*)]
    (with-bindings @bindings
      (try
        (clojure.main/repl
          ;; clojure.main/repl paves over certain vars even if they're already thread-bound
          :init #(do (set! *compile-path* (@bindings #'*compile-path*))
                   (set! *1 (@bindings #'*1))
                   (set! *2 (@bindings #'*2))
                   (set! *3 (@bindings #'*3))
                   (set! *e (@bindings #'*e)))   
          :read (if (string? code)
                  (let [reader (LineNumberingPushbackReader. (StringReader. code))]
                    #(read reader false %2))
                  (let [q (java.util.concurrent.ArrayBlockingQueue. (count code) false code)]
                    #(or (.poll q 0 TimeUnit/MILLISECONDS) %2)))
          :prompt (fn [])
          :need-prompt (constantly false)
          ; TODO pretty-print?
          :print (fn [v]
                   (reset! bindings (assoc (get-thread-bindings)
                                           #'*3 *2
                                           #'*2 *1
                                           #'*1 v))
                   (.flush ^Writer err)
                   (.flush ^Writer out)
                   (t/send transport (response-for msg
                                                   {:value v
                                                    :ns (-> *ns* ns-name str)})))
          ; TODO customizable exception prints
          :caught (fn [e]
                    (let [root-ex (#'clojure.main/root-cause e)]
                      (when-not (instance? ThreadDeath root-ex)
                        (reset! bindings (assoc (get-thread-bindings) #'*e e))
                        (t/send transport (response-for msg {:status :eval-error
                                                             :ex (-> e class str)
                                                             :root-ex (-> root-ex class str)}))
                        (clojure.main/repl-caught e)))))
        @bindings
        (finally
          (.flush ^Writer out)
          (.flush ^Writer err))))))

(defn- configure-thread-factory
  "Returns a new ThreadFactory for the given session.  This implementation
   generates daemon threads, with names that include the session id."
  []
  (let [session-thread-counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable
                (format "nREPL-worker-%s" (.getAndIncrement session-thread-counter)))
          (.setDaemon true))))))

(def ^{:private true} jdk6? (try
                              (Class/forName "java.util.ServiceLoader")
                              true
                              (catch ClassNotFoundException e false)))

(defn- configure-executor
  "Returns a ThreadPoolExecutor, configured (by default) to
   have no core threads, use an unbounded queue, create only daemon threads,
   and allow unused threads to expire after 30s."
  [& {:keys [keep-alive queue thread-factory]
      :or {keep-alive 30000
           queue (LinkedBlockingQueue.)}}]
  ; ThreadPoolExecutor in JDK5 *will not run* submitted jobs if the core pool size is zero and
  ; the queue has not yet rejected a job (see http://kirkwylie.blogspot.com/2008/10/java5-vs-java6-threadpoolexecutor.html)
  (ThreadPoolExecutor. (if jdk6? 0 1) Integer/MAX_VALUE
                       (long 30000) TimeUnit/MILLISECONDS
                       queue
                       (or thread-factory (configure-thread-factory))))

; A little mini-agent implementation. Needed because agents cannot be used to host REPL
; evaluation: http://dev.clojure.org/jira/browse/NREPL-17
(defn- prep-session
  [session]
  (locking session
    (returning session
      (when-not (-> session meta :queue)
        (alter-meta! session assoc :queue (atom clojure.lang.PersistentQueue/EMPTY))))))

(declare run-next)
(defn- run-next*
  [session executor]
  (let [qa (-> session meta :queue)]
    (loop []
      (let [q @qa
            qn (pop q)]
        (if-not (compare-and-set! qa q qn)
          (recur)
          (when (seq qn)
            (.execute executor (run-next session executor (peek qn)))))))))

(defn- run-next
  [session executor f]
  #(try
     (f)
     (finally
       (run-next* session executor))))

(defn- queue-eval
  "Queues the function for the given session."
  [session executor f]
  (let [qa (-> session prep-session meta :queue)]
    (loop []
      (let [q @qa]
        (if-not (compare-and-set! qa q (conj q f))
          (recur)
          (when (empty? q)
            (.execute executor (run-next session executor f))))))))

(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h & {:keys [executor] :or {executor (configure-executor)}}]
  (fn [{:keys [op session interrupt-id id transport]  :as msg}]
    (case op
      "eval"
      (if-not (:code msg)
        (t/send transport (response-for msg :status #{:error :no-code}))
        (queue-eval session executor
          (comp
            (partial reset! session)
            (fn []
              (alter-meta! session assoc
                           :thread (Thread/currentThread)
                           :eval-msg msg)
              (binding [*msg* msg]
                (returning (dissoc (evaluate @session msg) #'*msg*)
                  (t/send transport (response-for msg :status :done))
                  (alter-meta! session dissoc :thread :eval-msg)))))))
      
      "interrupt"
      ; interrupts are inherently racy; we'll check the agent's :eval-msg's :id and
      ; bail if it's different than the one provided, but it's possible for
      ; that message's eval to finish and another to start before we send
      ; the interrupt / .stop.
      (let [{:keys [id eval-msg ^Thread thread]} (meta session)]
        (if (or (not interrupt-id)
                (= interrupt-id (:id eval-msg)))
          (if-not thread
            (t/send transport (response-for msg :status #{:done :session-idle}))
            (do
              ; notify of the interrupted status before we .stop the thread so
              ; it is received before the standard :done status (thereby ensuring
              ; that is stays within the scope of a clojure.tools.nrepl/message seq
              (t/send transport {:status #{:interrupted}
                                 :id (:id eval-msg)
                                 :session id})
              (.stop thread)
              (t/send transport (response-for msg :status #{:done}))))
          (t/send transport (response-for msg :status #{:error :interrupt-id-mismatch :done}))))
      
      (h msg))))

