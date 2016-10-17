(ns grape.fixtures.comments
  (:require [grape.store :as store]
            [schema.core :as s]
            [grape.hooks.core :refer [hooks]]
            [grape.schema :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [grape.core :refer [read-item partial-update-resource]]
            [grape.graphql.core :refer [build-schema]]
            [grape.graphql.route :refer [relay-handler]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [grape.http :refer :all]
            [bidi.ring :refer [make-handler]]
            [grape.rest.route :refer [build-resources-routes]])
  (:import (org.bson.types ObjectId)
           (org.joda.time DateTime)
           (graphql GraphQL)
           (graphql.execution.batched BatchedExecutionStrategy)
           (clojure.lang ExceptionInfo)))

(def fixtures {"users"    [{:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :username "user 1" :email "user1@c1.com" :password "secret" :godchild (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2")}
                           {:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :username "user 2" :email "user2@c1.com" :password "secret" :friends [(ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3")]}
                           {:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :username "user 3" :email "user3@c2.com"}]
               "comments" [{:_id (ObjectId. "ccccccccccccccccccccccc1") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :text "this company is cool" :extra "extra field"}
                           {:_id (ObjectId. "ccccccccccccccccccccccc2") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "love you guys :D"}
                           {:_id (ObjectId. "ccccccccccccccccccccccc3") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "spam"}
                           {:_id (ObjectId. "ccccccccccccccccccccccc4") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :text "has been deleted" :_deleted true}]
               "likes"    [{:_id (ObjectId. "fffffffffffffffffffffff1") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :comment (ObjectId. "ccccccccccccccccccccccc2")}]})

(def UsersResource
  {:datasource         {:source "users"}
   :grape.graphql/type 'User
   :schema             {(? :_id)       ObjectId
                        :username      (s/both
                                         (s/constrained s/Str #(re-matches #"^[A-Za-z0-9_ ]{2,25}$" %) "username-should-be-valid")
                                         (s/constrained s/Str (unique? :username) "username-should-be-unique"))
                        (? :type)      (s/maybe (s/constrained s/Str #{"publisher" "user"} "type-should-by-valid"))
                        :email         (s/constrained s/Str email? "email-should-be-valid")
                        :password      (hidden s/Str)
                        (? :comments)  (read-only [(resource-join :comments :user)])
                        (? :godfather) (read-only (resource-join :public-users :godchild))
                        (? :godchild)  (s/maybe (resource-embedded :public-users :godchild ObjectId))
                        (? :friends)   [(resource-embedded :users :friends ObjectId)]
                        (? :_created)  (read-only DateTime)
                        (? :_updated)  (read-only DateTime)}
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
   :schema             {:_id           ObjectId
                        :username      s/Str
                        (? :comments)  (read-only [(resource-join :comments :user)])
                        (? :godfather) (read-only (resource-join :public-users :godchild))
                        (? :godchild)  (s/maybe (resource-embedded :public-users :godchild ObjectId))
                        (? :friends)   [(resource-embedded :public-users :friends ObjectId)]}
   :url                "public_users"
   :operations         #{:read}})

(def CommentsResource
  {:datasource         {:source "comments"}
   :grape.graphql/type 'Comment
   :schema             {(? :_id)          ObjectId
                        (? :user)         (s/maybe (resource-embedded :public-users :user ObjectId))
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
             :default-sort     {:_created -1}
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

(def app-routes
  (fn [deps]
    (make-handler ["/" (concat
                         (build-resources-routes deps)
                         [["relay" (partial relay-handler deps)]]
                         [[true (fn [_] {:status 404 :body {:_status 404 :_message "not found"}})]])])))

(defn exception-middleware
  "catches ExceptionInfos and returns the relevant HTTP response"
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch ExceptionInfo ex
        (let [type (:type (ex-data ex))
              issues (:issues (ex-data ex))
              body (merge
                     {:_error (.getMessage ex)}
                     (when issues {:_issues issues}))]
          (case type
            :unauthorized
            {:status 401
             :body   body}
            :forbidden
            {:status 403
             :body   body}
            :not-found
            {:status 404
             :body   body}
            :bad-request
            {:status 422
             :body   body}
            (do
              (prn ex)
              {:status 500
               :body   body})))))))

(defn the-wall
  "last guard before the north, in case of a beyond-the-wall json parse attack for instance"
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (prn e)
        {:status 500
         :body   (.getMessage e)}))))

(defn app-wrapper [deps]
  (fn [handler]
    (-> handler
        wrap-json-response
        (wrap-json-body {:keywords? true})
        (wrap-jwt-auth (:jwt config))
        wrap-params
        exception-middleware
        the-wall
        (wrap-cors identity))))

(def deps {:store              (store/new-mongo-datasource (:mongo-db config))
           :resources-registry resources-registry
           :graphql            (GraphQL. (build-schema {:resources-registry resources-registry}) (BatchedExecutionStrategy.))
           :hooks              hooks
           :config             config
           :http-server        (new-http-server (:http-server config) app-routes app-wrapper
                                                [:store :resources-registry :config :hooks :graphql])})

(defn load-fixtures [{{db :db} :store}]
  (doseq [[coll docs] fixtures]
    (mc/drop db coll)
    (doseq [doc docs]
      (mc/insert db coll doc))))

;; This query tests the four kinds of join capabilities
;; DB requests are optimized and should not query more than 6 times (including one for fetching the comments list)
;; * Single embedded resource -> comment.user + like.user (2 more)
;; * Many embedded resources -> user.friends (1 more)
;; * Single join resource -> user.godfather (1 more)
;; * Many join resources -> comment.likes (1 more)
(def CommentsListQuery
  "query CommentsListQuery($first: Int, $sort: String, $find: String) {
    CommentsList(first: $first, sort: $sort, find: $find) {
      edges {
        cursor
        node {
          id
          text
          user {
            username
            friends {
              id
              username
            }
            godfather {
              id
              username
            }
          }
          likes {
            user {
              username
            }
          }
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
      user {
        username
      }
    }
  }")