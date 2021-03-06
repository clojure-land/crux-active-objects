(ns avisi.crux.bootstrap.active-objects
  (:require [crux.db :as db]
            [crux.tx :as tx]
            [crux.bootstrap :as b]
            [crux.lru :as lru]
            [avisi.crux.active-objects :as ao-tx-log]
            [crux.io :as cio]
            [clojure.tools.logging :as log])
  (:import [java.util Date]
           [java.io Closeable]
           [crux.api ICruxAPI]
           [net.java.ao Query EntityStreamCallback]
           [com.atlassian.activeobjects.external ActiveObjects]
           [avisi.crux.tx EventLogEntry]))

(set! *warn-on-reflection* true)

(defn highest-id [^ActiveObjects ao]
  (first
   (map
    (fn [^EventLogEntry tx] (.getID tx))
    (.find ao ^Class EventLogEntry (-> (Query/select)
                                       (.limit 1)
                                       (.order "ID DESC"))))))

(def batch-limit 10000)
(def idle-sleep-ms 100)

(defn- event-log-consumer-main-loop [{:keys [indexer ^ActiveObjects ao running? listeners]}]
  (while @running?
    (let [start-offset (get-in (db/read-index-meta indexer :crux.tx-log/consumer-state)
                               [:crux.tx/event-log
                                :next-offset])
          ended-offset (volatile! nil)
          end-time (volatile! nil)]
      (log/debug "Start streaming from event-log start-offset=" start-offset)
      (.stream ao
               ^Class EventLogEntry
               ^Query (-> (Query/select "ID, TOPIC, TIME, BODY, KEY")
                          (.limit batch-limit)
                          (.order "ID ASC")
                          (.where "ID >= ?" (into-array Object [start-offset])))
               (reify EntityStreamCallback
                 (onRowRead [_ t]
                   (if-not @running?
                     (log/warn "Tried to index event-log entries while the event-log is already closed")
                     ;; Needed because hinting the onRowRead breaks Clojure finding the interface impl
                     (let [entry ^EventLogEntry t]
                       (let [tx-time (Date. ^long (.getTime entry))]
                         (log/debug "reading new entry in event log" {:body (.getBody entry)
                                                                      :key (.getKey entry)
                                                                      :id (.getID entry)
                                                                      :tx-time tx-time})
                         (try
                           (let [body (.getBody entry)
                                 clj-body (ao-tx-log/str->clj body)]
                             (case (.getTopic entry)
                               "doc" (db/index-doc indexer
                                                   (.getKey entry)
                                                   clj-body)
                               "tx" (db/index-tx
                                     indexer
                                     clj-body
                                     tx-time
                                     (.getID entry))))
                           (catch Exception e
                             (log/error e "Failed to index event log entry" {:body (.getBody entry)
                                                                             :id (.getID entry)})))
                         (vreset! end-time tx-time)
                         (vreset! ended-offset (.getID entry))))))))
      (log/debug "Done streaming from event-log to-offset=" (or @ended-offset start-offset))
      (when (and @running? (some? @end-time) (some? @ended-offset))
        (let [end-offset (highest-id ao)
              next-offset (inc (long @ended-offset))
              lag (- end-offset next-offset)
              consumer-state {:crux.tx/event-log
                              {:lag lag
                               :next-offset next-offset
                               :time @end-time}}]
          (log/debug "Event log consumer state:" (pr-str consumer-state))
          (db/store-index-meta indexer :crux.tx-log/consumer-state consumer-state)
          (log/debug "Calling listeners" (keys @listeners))
          (run!
           (fn [[k f]]
             (try
               (f consumer-state)
               (catch Exception e
                 (log/error e "Calling listener failed" {:listener-key k}))))
           @listeners)
          (when (and (pos? lag))
            (when (> lag batch-limit)
              (log/warn "Falling behind" ::event-log "at:" next-offset "end:" end-offset))))))
    (Thread/sleep idle-sleep-ms)))

(defn start-event-log-consumer! ^Closeable [indexer tx-log]

  (when-not (db/read-index-meta indexer :crux.tx-log/consumer-state)
    (db/store-index-meta
     indexer
     :crux.tx-log/consumer-state {:crux.tx/event-log {:lag 0
                                                      :next-offset 0
                                                      :time nil}}))

  (let [running? (atom true)
        worker-thread (doto (Thread. ^Runnable (fn []
                                                 (loop []
                                                   (let [{:keys [thrown-exception]} (try
                                                                                      (event-log-consumer-main-loop
                                                                                       {:indexer indexer
                                                                                        :listeners (:listeners tx-log)
                                                                                        :ao (:ao tx-log)
                                                                                        :running? running?})
                                                                                      (catch Throwable t
                                                                                        (log/fatal t "Event log consumer threw exception, consumption has stopped, will try again in 20 seconds..")
                                                                                        {:thrown-exception t}))]
                                                     (when thrown-exception
                                                       (Thread/sleep 20000)
                                                       (recur)))))
                                     "crux.tx.event-log-consumer-thread")
                        (.start))]
    (reify Closeable
      (close [_]
        (reset! running? false)
        (.join worker-thread)))))

(defn start-ao-node ^ICruxAPI [{:keys [ao db-dir doc-cache-size] :as options
                                :or {doc-cache-size (:doc-cache-size b/default-options)}}]
  (log/debugf "Starting crux with db-dir=%s " db-dir)
  (let [kv-store (b/start-kv-store options)
        object-store (lru/new-cached-object-store kv-store doc-cache-size)
        tx-log (ao-tx-log/map->ActiveObjectsTxLog {:ao ao
                                                   :listeners (atom {})})
        indexer (tx/->KvIndexer kv-store tx-log object-store)
        event-log-consumer (start-event-log-consumer! indexer tx-log)]
    (b/map->CruxNode {:kv-store kv-store
                      :tx-log tx-log
                      :object-store object-store
                      :indexer indexer
                      :event-log-consumer event-log-consumer
                      :options options
                      :close-fn (fn []
                                  (doseq [c [event-log-consumer tx-log kv-store object-store]]
                                    (cio/try-close c)))})))

