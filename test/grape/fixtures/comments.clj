(ns grape.fixtures.comments
  (:require [grape.store :as store]
            [schema.core :as s]
            [grape.hooks.core :refer [hooks]]
            [grape.schema :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [grape.core :refer [read-item partial-update-resource]]
            [grape.graphql.core :refer [build-schema]])
  (:import (org.bson.types ObjectId)
           (org.joda.time DateTime)
           (graphql GraphQL)
           (graphql.execution.batched BatchedExecutionStrategy)))

(def db (mg/get-db (mg/connect) "test"))

(def fixtures {"users"    [{:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :username "user 1" :email "user1@c1.com" :password "secret"}
                           {:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :username "user 2" :email "user2@c1.com" :password "secret"}
                           {:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :username "user 3" :email "user3@c2.com"}]
               "comments" [{:_id (ObjectId. "ccccccccccccccccccccccc1") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :text "this company is cool" :extra "extra field"}
                           {:_id (ObjectId. "ccccccccccccccccccccccc2") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "love you guys :D"}
                           {:_id (ObjectId. "ccccccccccccccccccccccc3") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "spam"}
                           {:_id (ObjectId. "ccccccccccccccccccccccc4") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "has been deleted" :_deleted true}]
               "likes"    []})

(def store-inst
  (store/map->MongoDataSource {:db db}))

(def UsersResource
  {:datasource         {:source "users"}
   :grape.graphql/type 'User
   :schema             {(? :_id)      ObjectId
                        :username     (s/constrained s/Str
                                                     #(re-matches #"^[A-Za-z0-9_ ]{2,25}$" %)
                                                     "username-should-be-valid")
                        :email        (s/constrained s/Str
                                                     email?)
                        :password     (hidden s/Str)
                        (? :comments) (read-only [(resource-join :comments :user)])
                        (? :friends)  [(resource-embedded :users :friends ObjectId)]
                        (? :_created) (read-only DateTime)
                        (? :_updated) (read-only DateTime)}
   :url                "users"
   :operations         #{:create :read :update :delete}
   :public-operations  #{:create}
   :auth-strategy      {:type       :field
                        :auth-field :user
                        :doc-field  :_id}
   :item-aliases       [["me" (fn [deps resource request]
                                (get-in request [:auth :user]))]]})

; public-users is a view of the users collection
; read only and safe for public exposure
(def PublicUsersResource
  {:datasource         {:source "users"}
   :grape.graphql/type 'PublicUser
   :schema             {:_id      s/Str
                        :username s/Str}
   :url                "public_users"
   :operations         #{:read}})

(def CommentsResource
  {:datasource         {:source "comments"}
   :grape.graphql/type 'Comment
   :schema             {(? :_id)          ObjectId
                        :user             (resource-embedded :public-users :user ObjectId)
                        :text             s/Str
                        (? :parent)       (s/maybe (resource-embedded :comments :parent ObjectId))
                        (? :last_replies) (read-only [(resource-embedded :comments :last_replies ObjectId)])
                        (? :likes)        (read-only [(resource-join :likes :comment)])
                        (? :statistics)   (read-only {:likes s/Int})
                        (? :_created)     (read-only DateTime)
                        (? :_updated)     (read-only DateTime)}
   :url                "comments"
   :operations         #{:create :read :update :delete}
   :public-operations  #{:read}
   :auth-strategy      {:type       :field
                        :auth-field :user
                        :doc-field  :user}
   :soft-delete        true
   :post-create        (fn [deps resource request payload]
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
  {:datasource         {:source "likes"}
   :grape.graphql/type 'Like
   :schema             {(? :_id) ObjectId
                        :user    (resource-embedded :public-users :user ObjectId)
                        :comment (resource-embedded :comments :comment ObjectId)}
   :url                "likes"
   :operations         #{:create :read :delete}
   :public-operations  #{:read}
   :auth-strategy      {:type       :field
                        :auth-field :user
                        :doc-field  :user}
   :post-create        (fn [deps resource request payload]
                         ; update the like counter on the corresponding comment
                         (mc/update (get-in deps [:store :db]) "comments"
                                    {:_id (:comment payload)}
                                    {:$inc {:statistics.likes 1}})
                         payload)})

(def config {:http-server      {:host "localhost" :port 8080}
             :default-paginate {:per-page 10}
             :default-sort     {:sort {:_created -1}}
             :jwt              {:audience    "api"
                                :secret      "secret"
                                :auth-schema {:user ObjectId
                                              s/Any s/Any}}
             :mongo-db         {:uri "mongodb://localhost:27017/test"}})

(def resources-registry
  {:users        UsersResource
   :public-users PublicUsersResource
   :comments     CommentsResource
   :likes        LikesResource})

(def deps {:store              store-inst
           :resources-registry resources-registry
           :graphql            (GraphQL. (build-schema {:resources-registry resources-registry}) (BatchedExecutionStrategy.))
           :hooks              hooks
           :config             config})

(defn load-fixtures []
  (doseq [[coll docs] fixtures]
    (mc/drop db coll)
    (doseq [doc docs]
      (mc/insert db coll doc))))

(def CommentsListQuery
  "query CommentsListQuery($first: Int, $sort: String, $find: String) {
    CommentsList(first: $first, sort: $sort, find: $find) {
      edges {
        cursor
        node {
          id
          text
        }
      }
      pageInfo {
        hasNextPage
      }
    }
  }")

(def CommentsQuery
  "query CommentsQuery {
    Comments(id:\"ccccccccccccccccccccccc1\") {
      id
      text
    }
  }")