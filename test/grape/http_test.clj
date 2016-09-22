(ns grape.http-test
  (:require [clojure.test :refer :all]
            [grape.http :refer :all]
            [grape.schema :refer :all]
            [bidi.ring :refer [make-handler]]
            [schema.core :as s]
            [grape.fixtures.comments :refer :all]
            [grape.rest.route :refer [handler-builder]]
            [grape.store :refer [map->MongoDataSource]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [clj-http.client :as client]
            [com.stuartsierra.component :as component]
            [grape.rest.route :refer [build-resources-routes]]
            [grape.store :as store])
  (:import (clojure.lang ExceptionInfo)
           (org.bson.types ObjectId)))

;; token generated with jwt.io
;; {"aud": "api", "user-id": "57503897eeb06b64ada8fa08"}
(def token "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhcGkiLCJzdWIiOiIxMjM0NTY3ODkwIiwidXNlci1pZCI6IjU3NTAzODk3ZWViMDZiNjRhZGE4ZmEwOCJ9.KCVn0lDXiHnYJJp7DxEn5fEwPhF4O-HEGHDCqvl6Z4Y")

(deftest auth-middleware
  (testing "request is correctly enriched"
    (let [;; token generated with jwt.io
          ;; {"aud": "api", "user-id": "57503897eeb06b64ada8fa08"}
          token "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhcGkiLCJzdWIiOiIxMjM0NTY3ODkwIiwidXNlci1pZCI6IjU3NTAzODk3ZWViMDZiNjRhZGE4ZmEwOCJ9.KCVn0lDXiHnYJJp7DxEn5fEwPhF4O-HEGHDCqvl6Z4Y"
          request {:query-params {"access_token" token}}
          ;; we setup a simple handler that responds with the incoming request
          handler (wrap-jwt-auth identity {:audience "api" :secret "secret" :auth-schema {:user-id ObjectId s/Any s/Any}})
          response (handler request)]
      (is (= (ObjectId. "57503897eeb06b64ada8fa08") (get-in response [:auth :user-id]))))
    )

  (testing "request jwt signature invalid"
    (let [;; invalid token
          token "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhcGkiLCJzdWIiOiIxMjM0NTY3ODkwIiwidXNlci1pZCI6IjU3NTAzODk3ZWViMDZiNjRhZGE4ZmEwOCJ9.KCVn0lDXiHnYJJp7DxEn5fEwPhF4O-HEGHDCqvl6Z4Ya"
          request {:query-params {"access_token" token}}
          ;; we setup a simple handler that responds with the incoming request
          handler (wrap-jwt-auth identity {:audience "api" :secret "secret" :auth-schema {:user-id ObjectId s/Any s/Any}})
          response (handler request)]
      (is (nil? (:auth response)))))

  (testing "request jwt expired"
    (let [;; invalid token
          token "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhcGkiLCJ1c2VyLWlkIjoiNTc1MDM4OTdlZWIwNmI2NGFkYThmYTA4IiwiZXhwIjoxNDY5MTgyMDIzfQ.fabF9L5JEHo8SG6B_5cebmez7WdmLPmd3CJGRSjOPyg"
          request {:query-params {"access_token" token}}
          ;; we setup a simple handler that responds with the incoming request
          handler (wrap-jwt-auth identity {:audience "api" :secret "secret" :auth-schema {:user-id ObjectId s/Any s/Any}})
          response (handler request)]
      (is (nil? (:auth response)))))

  (testing "request audience invalid"
    (let [;; invalid token
          token "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJpbnZhbGlkIiwidXNlci1pZCI6IjU3NTAzODk3ZWViMDZiNjRhZGE4ZmEwOCJ9.-DJBqvuoMP-rgc4L7-3CpB4CQzKjWJcVZUahHeQOpnw"
          request {:query-params {"access_token" token}}
          ;; we setup a simple handler that responds with the incoming request
          handler (wrap-jwt-auth identity {:audience "api" :secret "secret" :auth-schema {:user-id ObjectId s/Any s/Any}})
          response (handler request)]
      (is (nil? (:auth response))))))

(deftest http-server-test
  (testing "fetch me through fully started component system"
    (load-fixtures)
    (let [app-routes
          (fn [deps]
            (make-handler ["/" (concat
                                 (build-resources-routes deps)
                                 [[true (fn [_] {:status 404 :body {:_status 404 :_message "not found"}})]])]))
          app-wrapper
          (fn [deps]
            (fn [handler]
              (-> handler
                  (wrap-cors identity)
                  wrap-json-response
                  (wrap-json-body {:keywords? true})
                  (wrap-jwt-auth (get-in deps [:config :jwt]))
                  wrap-params)))
          system (component/start (component/system-map
                                    :resources-registry (:resources-registry deps)
                                    :hooks (:hooks deps)
                                    :config (:config deps)
                                    :store (store/new-mongo-datasource (get-in deps [:config :mongo-db]))
                                    :http-server (new-http-server (get-in deps [:config :http-server]) app-routes app-wrapper
                                                                  [:store :resources-registry :config :hooks])))]
      ;; no token provided
      (is (thrown-with-msg? ExceptionInfo #"status 401" (client/get "http://localhost:8080/me")))
      ;; token schema invalid
      (is (thrown-with-msg? ExceptionInfo #"status 401" (client/get "http://localhost:8080/me?access_token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhcGkiLCJ1c2VyLWlkIjoiYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWExIn0.835-6ptNKv0pLcWLu6GtIOIjj9KfaquAhvdygUAaPZ0")))
      (is (thrown-with-msg? ExceptionInfo #"status 404" (client/get "http://localhost:8080/unknown")))
      (is (= (get-in
               (client/get "http://localhost:8080/me?access_token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyIjoiYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWExIiwiYXVkIjoiYXBpIn0.RWIwbbgk7QUcmOzhf7Z19ifr0AzcLVZ_z2CMYuIVPnM" {:as :json})
               [:body :username])
             "user 1"))
      (component/stop system)))

  (testing "options request always ok through fully started component system"
    (load-fixtures)
    (let [app-routes
          (fn [deps]
            (make-handler ["/" (concat
                                 (build-resources-routes deps)
                                 [[true (fn [_] {:status 404 :body {:_status 404 :_message "not found"}})]])]))
          app-wrapper
          (fn [deps]
            (fn [handler]
              (-> handler
                  (wrap-cors identity)
                  wrap-json-response
                  (wrap-json-body {:keywords? true})
                  (wrap-jwt-auth (get-in deps [:config :jwt]))
                  wrap-params)))
          system (component/start (component/system-map
                                    :resources-registry (:resources-registry deps)
                                    :hooks (:hooks deps)
                                    :config (:config deps)
                                    :store (store/new-mongo-datasource (get-in deps [:config :mongo-db]))
                                    :http-server (new-http-server (get-in deps [:config :http-server]) app-routes app-wrapper
                                                                  [:store :resources-registry :config :hooks])))]
      (is (= 200 (:status (client/options "http://localhost:8080/me"))))
      (component/stop system))))