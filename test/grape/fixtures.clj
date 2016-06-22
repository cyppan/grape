(ns grape.fixtures
  (:require [grape.store :as store]
            [schema.core :as s]
            [grape.hooks.core :refer [hooks]]
            [schema.spec.core :as spec]
            [schema.spec.leaf :as leaf]
            [grape.schema :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [grape.core :refer [read-item partial-update-resource]]))

(def db (mg/get-db (mg/connect) "test"))

(def fixtures {"users"    [{:_id (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :username "user 1" :email "user1@c1.com" :password "secret"}
                           {:_id (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :username "user 2" :email "user2@c1.com" :password "secret"}
                           {:_id (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :username "user 3" :email "user3@c2.com"}]
               "comments" [{:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc1") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :text "this company is cool" :extra "extra field"}
                           {:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc2") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "love you guys :D"}
                           {:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc3") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "spam"}
                           {:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc4") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "has been deleted" :_deleted true}]})

(def store-inst
  (store/map->MongoDataSource {:db db}))

(def UsersResource
  {:datasource        {:source "users"}
   :schema            {(? :_id)      ObjectId
                       :username     (Field Str
                                            #(re-matches #"^[A-Za-z0-9_ ]{2,25}$" %)
                                            "username-should-be-valid")
                       :email        EmailField
                       :password     (write-only
                                       Str)
                       (? :comments) (read-only [(vary-meta
                                                   Any
                                                   merge {:grape/type 'Comment
                                                          :grape/relation-spec
                                                                      {:type     :join
                                                                       :resource :comments
                                                                       :field    :user}})])
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
   :schema     {:_id      Str
                :username Str}
   :url        "public_users"
   :operations #{:read}})

(def CommentsResource
  {:datasource        {:source "comments"}
   :schema            {(? :_id)          ObjectId
                       :user             (ResourceField ObjectId :public-users)
                       :text             Str
                       (? :parent)       (maybe
                                           (ResourceField ObjectId :comments))
                       (? :last_replies) (read-only
                                           [(ResourceField ObjectId :comments)])
                       (? :likes)        (read-only
                                           [(vary-meta
                                              Any
                                              merge {:grape/type 'Like
                                                     :grape/relation-spec
                                                                 {:type     :join
                                                                  :resource :likes
                                                                  :field    :comment}})])
                       (? :statistics)   (read-only
                                           {:likes Int})
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
                        ; to optimize requests
                        (when-let [parent (:parent payload)]
                          (mc/update (get-in deps [:store :db]) "comments"
                                     {:_id parent}
                                     {:$push {:last_replies
                                              {:$each  [(:_id payload)]
                                               :$slice -3}}}))
                        payload)})

(def LikesResource
  {:datasource        {:source "likes"}
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

(def config {:default-paginate {:per-page 10}
             :default-sort     {:sort {:_created -1}}})

(def deps {:store              store-inst
           :resources-registry {:users        UsersResource
                                :public-users PublicUsersResource
                                :comments     CommentsResource
                                :likes        LikesResource}
           :hooks              hooks
           :config             config})

(defn load-fixtures []
  (doseq [[coll docs] fixtures]
    (mc/drop db coll)
    (doseq [doc docs]
      (mc/insert db coll doc))))
