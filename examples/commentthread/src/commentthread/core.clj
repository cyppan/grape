(ns commentthread.core
  (:require [monger.core :as mg]
            [grape.hooks.core :refer [hooks]]
            [grape.schema :refer :all]
            [grape.rest.route :refer [handler-builder]]
            [grape.store :refer [map->MongoDataSource]]
            [grape.http :refer [wrap-jwt-auth]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [monger.collection :as mc])
  (:gen-class))

(def db (mg/get-db (mg/connect) "commentthread"))

(def UsersResource
  {:datasource        {:source "users"}
   :type              'User
   :schema            {(? :_id)      ObjectId
                       :username     (Field Str
                                            #(re-matches #"^[A-Za-z0-9_ ]{2,25}$" %)
                                            "username-should-be-valid")
                       :email        EmailField
                       :password     (write-only Str)
                       (? :comments) (read-only [(ResourceJoin :comments :user 'Comment)])
                       (? :friends)  [(ResourceField ObjectId :users)]
                       (? :_created) (read-only DateTime)
                       (? :_updated) (read-only DateTime)}
   :url               "users"
   :operations        #{:create :read :update :delete}
   :public-operations #{:create}
   :auth-strategy     {:type       :field
                       :auth-field :user
                       :doc-field  :_id}
   :item-aliases      [["me" (fn [deps resource request]
                               (get-in request [:auth :user]))]]})

; public-users is a view of the users collection
; read only and safe for public exposure
(def PublicUsersResource
  {:datasource {:source "users"}
   :type       'User
   :schema     {:_id      Str
                :username Str}
   :url        "public_users"
   :operations #{:read}})

(def CommentsResource
  {:datasource        {:source "comments"}
   :type              'Comment
   :schema            {(? :_id)          ObjectId
                       :user             (ResourceField ObjectId :public-users)
                       :text             Str
                       (? :parent)       (maybe (ResourceField ObjectId :comments))
                       (? :last_replies) (read-only [(ResourceField ObjectId :comments)])
                       (? :likes)        (read-only [(ResourceJoin :likes :comment 'Like)])
                       (? :statistics)   (read-only {:likes Int})
                       (? :_created)     (read-only DateTime)
                       (? :_updated)     (read-only DateTime)}
   :url               "comments"
   :operations        #{:create :read :update :delete}
   :public-operations #{:read}
   :auth-strategy     {:type       :field
                       :auth-field :user
                       :doc-field  :user}
   :soft-delete       true
   :post-create       (fn [deps resource request payload]
                        ; If this is a reply
                        ; maintain an array of the three last replies on it
                        ; to optimize fetching
                        (when-let [parent (:parent payload)]
                          (mc/update (get-in deps [:store :db]) "comments"
                                     {:_id parent}
                                     {:$push {:last_replies
                                              {:$each  [(:_id payload)]
                                               :$slice -3}}}))
                        payload)})

(def LikesResource
  {:datasource        {:source "likes"}
   :type              'Like
   :schema            {(? :_id) ObjectId
                       :user    (ResourceField ObjectId :public-users)
                       :comment (ResourceField ObjectId :comments)}
   :url               "likes"
   :operations        #{:create :read :delete}
   :public-operations #{:read}
   :auth-strategy     {:type       :field
                       :auth-field :user
                       :doc-field  :user}
   :post-create       (fn [deps resource request payload]
                        ; update the like counter on the corresponding comment
                        (mc/update (get-in deps [:store :db]) "comments"
                                   {:_id (:comment payload)}
                                   {:$inc {:statistics.likes 1}})
                        payload)})

(def OplogResource
  {:datasource    {:source "oplog"}
   :url           "oplog"
   :schema        {:_id  ObjectId
                   :o    Str
                   :s    Str
                   :i    Str
                   :c    Any
                   :af   Str
                   :auth Any
                   :at   DateTime}
   :operations    #{:read}
   :auth-strategy {:type       :field
                   :auth-field :user
                   :doc-field  :af}})

(def deps {:config             {:default-language "fr"      ;; Errors are translated
                                :jwt              {:audience    "api"
                                                   :secret      "secret"
                                                   :auth-schema {:user ObjectId
                                                                 Any   Any}}}
           :translations       {:en {"should-be-even" "the field should be even"}
                                :fr {"should-be-even" "le champ doit être pair"}}
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
  (run-jetty grape-handler {:port 8080}))
