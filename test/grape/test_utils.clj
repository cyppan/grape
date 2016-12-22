(ns grape.test-utils
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [monger.core :as mg]
            [buddy.sign.jwt :as jwt]))

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
                   (assoc :db-flusher (new-db-flusher)))
        system (component/start-system system)]
    (body-fn system)
    (component/stop-system system)))

(defn encode-jwt [{{{:keys [audience secret]} :jwt} :config} claims]
  (jwt/sign (assoc claims :aud audience) secret))
