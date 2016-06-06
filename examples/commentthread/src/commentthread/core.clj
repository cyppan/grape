(ns commentthread.core
  (:require [monger.core :as mg]
            [grape.hooks.core :refer [hooks]]
            [grape.schema :refer :all]
            [schema.core :as s]
            [grape.rest.route :refer [handler-builder]]
            [grape.store :refer [map->MongoDataSource]]
            [grape.http :refer [wrap-jwt-auth]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]])
  (:import (org.joda.time DateTime)
           (org.bson.types ObjectId))
  (:gen-class))

(def db (mg/get-db (mg/connect) "commentthread"))

(def UsersResource
  {:datasource        {:source "users"}
   :schema            {(? :_id)      ObjectId
                       :username     #"^[A-Za-z0-9_ ]{2,25}$"
                       (? :website)  (s/maybe Url)
                       (? :_created) (read-only DateTime)
                       (? :_updated) (read-only DateTime)}
   :url               "users"
   :operations        #{:create :read :update :delete}
   :public-operations #{:create}
   :auth-strategy     {:type       :field
                       :auth-field :user-id                 ;; extracted from a jwt token for example
                       :doc-field  :_id}})

(def PublicUsersResource
  {:datasource        {:source "users"}
   :schema            {}
   :fields            #{:_id :username}
   :url               "public_users"
   :operations        #{:read}
   :public-operations #{:read}})

(def CommentsResource
  {:datasource        {:source "comments"}
   :schema            {(? :_id)      ObjectId
                       :user         (s/constrained ObjectId (resource-exists :users) "resource-exists")
                       :text         s/Str
                       (? :_created) (read-only DateTime)
                       (? :_updated) (read-only DateTime)}
   :url               "comments"
   :operations        #{:create :read :update :delete}
   :public-operations #{:read}
   :auth-strategy     {:type       :field
                       :auth-field :user-id
                       :doc-field  :user}})

(def LikesResource
  {:datasource        {:source "likes"}
   :schema            {(? :_id)      ObjectId
                       :user         (s/constrained ObjectId (resource-exists :users) "resource-exists")
                       :comment      (s/constrained ObjectId (resource-exists :comments) "resource-exists")
                       (? :_created) (read-only DateTime)
                       (? :_updated) (read-only DateTime)}
   :url               "likes"
   :operations        #{:create :read :delete}
   :public-operations #{:read}
   :auth-strategy     {:type       :field
                       :auth-field :user-id
                       :doc-field  :user}})

(def OplogResource
  {:datasource {:source "oplog"}
   :url        "oplog"
   :schema     {}
   :operations #{:read}
   :fields     #{:_id :o :s :i :c :af :auth :at}
   :auth-strategy {:type :field
                   :auth-field :user-id
                   :doc-field :af}})

(def deps {:config             {:default-language "fr"      ;; Errors are translated
                                :jwt              {:audience "api"
                                                   :secret   "secret"}}
           :hooks              hooks
           :store              (map->MongoDataSource {:db db})
           :resources-registry {:users        UsersResource
                                :public-users PublicUsersResource
                                :comments     CommentsResource
                                :likes        LikesResource
                                :oplog        OplogResource}})

(def grape-handler
  (-> (handler-builder deps)
      (wrap-cors identity)
      wrap-json-response
      (wrap-json-body {:keywords? true})
      (wrap-jwt-auth (get-in deps [:config :jwt]))
      wrap-params))

(defn -main [& args]
  (run-jetty grape-handler {:port 3000}))
