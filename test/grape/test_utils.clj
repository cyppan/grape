(ns grape.test-utils
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [monger.core :as mg]))

(defrecord DbFlusher [store]
  component/Lifecycle
  (start [this]
    (log/info "flushing MongoDB")
    (mg/drop-db (:conn store) (.getName (:db store)))
    this)
  (stop [this]
    this))

(defn new-db-flusher []
  (component/using (map->DbFlusher {})
                   [:store]))

(defn with-test-system [system-map body-fn]
  (let [system (-> system-map
                   (assoc :db-flusher (new-db-flusher))
                   (dissoc :http-server))
        system (component/start-system system)]
    (body-fn system)
    (component/stop-system system)))
