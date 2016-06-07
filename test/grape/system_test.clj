(ns grape.system-test
  (:require [grape.store :as store]
            [schema.core :as s]
            [grape.hooks.core :refer [hooks]]
            [schema.spec.core :as spec]
            [schema.spec.leaf :as leaf]
            [grape.schema :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [grape.core :refer [read-item]]))

(def db (mg/get-db (mg/connect) "test"))

(def fixtures {"companies"   [{:_id (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :name "Pied Piper" :domain "www.piedpiper.com"}
                              {:_id (org.bson.types.ObjectId. "caccccccccccccccccccccc2") :name "Raviga" :domain "c2.com"}]
               "permissions" [{:_id (org.bson.types.ObjectId. "eeeeeeeeeeeeeeeeeeeeeee1") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :roles ["ADMIN"]}
                              {:_id (org.bson.types.ObjectId. "eeeeeeeeeeeeeeeeeeeeeee2") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :roles ["USER"]}
                              {:_id (org.bson.types.ObjectId. "eeeeeeeeeeeeeeeeeeeeeee3") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc2") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :roles ["ADMIN"]}]
               "users"       [{:_id (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :username "user 1" :email "user1@c1.com" :password "secret"}
                              {:_id (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :username "user 2" :email "user2@c1.com" :password "secret"}
                              {:_id (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc2") :username "user 3" :email "user3@c2.com"}]
               "comments"    [{:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc1") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :text "this company is cool" :extra "extra field"}
                              {:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc2") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :text "love you guys :D"}
                              {:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc3") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :text "spam"}
                              {:_id (org.bson.types.ObjectId. "ccccccccccccccccccccccc4") :user (org.bson.types.ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (org.bson.types.ObjectId. "caccccccccccccccccccccc1") :text "has been deleted" :_deleted true}]})

(def store-inst
  (store/map->MongoDataSource {:db db}))

(def CompaniesResource
  {:datasource        {:source "companies"}
   :schema            {(? :_id)      (Field ObjectId)
                       :name         (Field Str)
                       (? :domain)   UrlField
                       (? :roles)    (read-only [(Field String)])
                       (? :info)     (s/maybe {:gender (EnumField Str #{:male :female})})
                       (? :pages)    [{:url         UrlField
                                       :description (SizedField Str 5 80)}]
                       (? :features) (s/maybe {:premium (Field Bool)})}
   :url               "companies"
   :operations        #{:create :read :update :delete}
   :public-operations #{:create}
   :auth-strategy     {:type       :field
                       :auth-field :auth_id
                       :doc-field  :_id}
   :soft-delete       true})

(def UsersResource
  {:datasource        {:source "users"}
   :schema            {(? :_id)  (Field ObjectId)
                       :company  (ResourceField ObjectId :companies)
                       :username (Field Str
                                        #(re-matches #"^[A-Za-z0-9_ ]{2,25}$" %)
                                        "username-should-be-valid")
                       :email    EmailField
                       :password (Field Str)
                       (? :comments) (vary-meta
                                       (read-only [s/Any])
                                       merge {:grape/type 'Comments
                                              :grape/relation-spec {:type :join
                                                                    :arity :many
                                                                    :resource :comments
                                                                    :field :user}})}
   :fields            #{:_id :company :username :email}
   :url               "users"
   :operations        #{:create :read :update :delete}
   :public-operations #{:read :create}
   :auth-strategy     {:type       :field
                       :auth-field :auth_id
                       :doc-field  :_id}
   :extra-endpoints   [["me" (fn [deps resource request]
                               (read-item deps resource request {}))]]})

(def PublicUsersResource
  {:datasource {:source "users"}
   :schema     {}
   :fields     #{:_id :username}
   :url        "public_users"
   :operations #{:read}})

(def CompaniesPermissionsResource
  {:datasource    {:source "permissions"}
   :schema        {:_id     ObjectId
                   :company ObjectId
                   :user    ObjectId
                   :roles   [Str]}
   :url           "companies_permissions"
   :operations    #{:create :read :update :delete}
   :auth-strategy {:type       :field
                   :auth-field :auth_id
                   :doc-field  :account}})

(def UsersPermissionsResource
  (merge CompaniesPermissionsResource
         {:url           "users_permissions"
          :operations    #{:read :delete}
          :auth-strategy {:type       :field
                          :auth-field :auth_id
                          :doc-field  :user}}))

(def CommentsResource
  {:datasource        {:source "comments"}
   :schema            {(? :_id)      ObjectId
                       :user         (vary-meta
                                       (ResourceField ObjectId :public-users)
                                       merge {:grape/relation-spec {:type     :embedded
                                                                    :resource :public-users}})
                       (? :embedded) (maybe {:user (vary-meta
                                                     (ResourceField ObjectId :public-users)
                                                     merge {:grape/relation-spec {:type     :embedded
                                                                                  :resource :public-users}})})
                       (? :users) [(vary-meta
                                     (ResourceField ObjectId :public-users)
                                     merge {:grape/relation-spec {:type     :embedded
                                                                  :resource :public-users}})]
                       (? :company)  ObjectId
                       :text         Str
                       (? :_created) (read-only DateTime)
                       (? :_updated) (read-only DateTime)}
   :url               "comments"
   :operations        #{:create :read :update :delete}
   :public-operations #{:read}
   :auth-strategy     {:type       :field
                       :auth-field :auth_id
                       :doc-field  :user}
   :soft-delete       true})

(def config {:default-paginate {:per-page 10}
             :default-sort     {:sort {:_created -1}}})

(def deps {:store              store-inst
           :resources-registry {:companies             CompaniesResource
                                :users                 UsersResource
                                :public-users          PublicUsersResource
                                :companies-permissions CompaniesPermissionsResource
                                :users-permissions     UsersPermissionsResource
                                :comments              CommentsResource}
           :hooks              hooks
           :config             config})

(defn load-fixtures []
  (doseq [[coll docs] fixtures]
    (mc/drop db coll)
    (doseq [doc docs]
      (mc/insert db coll doc))))
