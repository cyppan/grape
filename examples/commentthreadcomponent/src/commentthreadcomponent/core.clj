(ns commentthreadcomponent.core
  (:require [com.stuartsierra.component :as component]
            [commentthreadcomponent.resources :refer [resources-registry]]
            [grape.rest.route :refer [handler-builder]]
            [grape.store :refer [map->MongoDataSource]]
            [grape.rest.route :refer [build-resources-routes]]
            [grape.http :refer [wrap-jwt-auth new-http-server]]
            [grape.hooks.core :refer [hooks]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [bidi.ring :refer [make-handler]]
            [grape.store :as store]
            [grape.schema :refer :all]
            [taoensso.timbre :as timbre])
  (:gen-class))

(timbre/merge-config! {:level :info})

(def config
  {:mongo-db    {:uri "mongodb://localhost:27017/commentthread"}
   :jwt         {:audience "api"
                 :secret   "secret"
                 :auth-schema {:user ObjectId
                               Any   Any}}
   :http-server {:host "0.0.0.0" :port 8081}})

(defn health-handler [_]
  {:status 200
   :body   {:_status 200 :_message "OK"}})

(defn not-found-handler [_]
  {:status 404
   :body   {:_status 404 :_message "Not Found"}})

(defn app-routes [deps]
  (make-handler ["/" (concat
                       [["/" health-handler]
                        ["health" health-handler]]
                       (build-resources-routes deps)
                       [[true not-found-handler]])]))

(defn app-wrapper [deps]
  (fn [handler]
    (-> handler
        (wrap-cors identity)
        wrap-json-response
        (wrap-json-body {:keywords? true})
        (wrap-jwt-auth (get-in deps [:config :jwt]))
        wrap-params)))

(defn app-system [config]
  (component/system-map
    :config config
    :hooks hooks
    :store (store/new-mongo-datasource (:mongo-db config))
    :resources-registry resources-registry
    :http-server (new-http-server (:http-server config) app-routes app-wrapper
                                  [:store :resources-registry :config :hooks])))

(defn -main [& args]
  (component/start (app-system config)))
