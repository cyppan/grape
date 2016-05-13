(ns grappe.core-test
  (:require [clojure.test :refer :all]
            [grappe.core :refer :all]
            [grappe.store :as store]
            [schema.core :as s]
            [grappe.hooks.core :refer [compose-hooks]]
            [grappe.hooks.auth-field :refer [hooks] :rename {hooks auth-field-hooks}]
            [grappe.hooks.default-sort :refer [hooks] :rename {hooks default-sort-hooks}]
            [grappe.hooks.restricts-fields :refer [hooks] :rename {hooks restrict-fields-hooks}]
            [grappe.hooks.inject-pagination :refer [hooks] :rename {hooks inject-pagination-hooks}]
            [grappe.query :refer [validate-query]])
  (:import (org.bson.types ObjectId)
           (clojure.lang ExceptionInfo)))

(def store-inst
  (store/->AtomDataSource
    (atom {"companies"   [{:_id (ObjectId. "caccccccccccccccccccccc1") :name "Pied Piper" :domain "www.piedpiper.com"}
                          {:_id (ObjectId. "caccccccccccccccccccccc2") :name "Raviga" :domain "c2.com"}]
           "permissions" [{:_id (ObjectId. "eeeeeeeeeeeeeeeeeeeeeee1") :company (ObjectId. "caccccccccccccccccccccc1") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :roles ["ADMIN"]}
                          {:_id (ObjectId. "eeeeeeeeeeeeeeeeeeeeeee2") :company (ObjectId. "caccccccccccccccccccccc1") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :roles ["USER"]}
                          {:_id (ObjectId. "eeeeeeeeeeeeeeeeeeeeeee3") :company (ObjectId. "caccccccccccccccccccccc2") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :roles ["ADMIN"]}]
           "users"       [{:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :company (ObjectId. "caccccccccccccccccccccc1") :username "user 1" :email "user1@c1.com" :password "secret"}
                          {:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (ObjectId. "caccccccccccccccccccccc1") :username "user 2" :email "user2@c1.com" :password "secret"}
                          {:_id (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa3") :company (ObjectId. "caccccccccccccccccccccc2") :username "user 3" :email "user3@c2.com"}]
           "comments"    [{:_id (ObjectId. "ccccccccccccccccccccccc1") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1") :company (ObjectId. "caccccccccccccccccccccc1") :text "this company is cool" :extra "extra field"}
                          {:_id (ObjectId. "ccccccccccccccccccccccc2") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (ObjectId. "caccccccccccccccccccccc1") :text "love you guys :D"}
                          {:_id (ObjectId. "ccccccccccccccccccccccc3") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (ObjectId. "caccccccccccccccccccccc1") :text "spam"}
                          {:_id (ObjectId. "ccccccccccccccccccccccc4") :user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2") :company (ObjectId. "caccccccccccccccccccccc1") :text "has been deleted" :_deleted true}]})))

(def CompaniesResource
  {:datasource              {:source "companies"}
   :schema                  {:_id    ObjectId
                             :name   s/Str
                             :domain s/Str}
   :url                     "companies"
   :resource-methods        #{:post :get}
   :public-resource-methods #{:post}
   :item-methods            #{:get :put :patch :delete}
   :auth-strategy           {:type       :field
                             :auth-field :auth_id
                             :doc-field  :_id}
   :soft-delete             true
   :relations               {:permissions {:type     :ref-field
                                           :arity    :many
                                           :path     [:permissions]
                                           :resource :companies-permissions
                                           :field    :company}
                             :users       {:type :ref-field}}})

(def UsersResource
  {:datasource       {:source "users"}
   :schema           {:_id      ObjectId
                      :company  ObjectId
                      :username #"^[A-Za-z0-9_ ]{2,25}$"
                      :email    s/Str
                      :password s/Str}
   :fields           #{:_id :company :username :email}
   :url              "users"
   :resource-methods #{:post :get}
   :item-methods     #{:get :put :patch :delete}
   :auth-strategy    {:type       :field
                      :auth-field :auth_id
                      :doc-field  :_id}
   :relations        {:permissions {:type     :ref-field
                                    :arity    :many
                                    :path     [:permissions]
                                    :resource :users-permissions
                                    :field    :user}
                      :comments    {:type     :ref-field
                                    :arity    :many
                                    :path     [:comments]
                                    :resource :comments
                                    :field    :user}}})

(def PublicUsersResource
  {:datasource {:source "users"}
   :schema     (select-keys (:schema UsersResource) [:_id :username])
   :url        "public_users"})

(def CompaniesPermissionsResource
  {:datasource       {:source "permissions"}
   :schema           {:_id     ObjectId
                      :company ObjectId
                      :user    ObjectId
                      :roles   [s/Str]}
   :url              "companies_permissions"
   :resource-methods #{:post :get}
   :item-methods     #{:get :put :patch :delete}
   :auth-strategy    {:type       :field
                      :auth-field :auth_id
                      :doc-field  :account}})

(def UsersPermissionsResource
  (merge CompaniesPermissionsResource
         {:url              "users_permissions"
          :resource-methods #{:get}
          :item-methods     #{:get :delete}
          :auth-strategy    {:type       :field
                             :auth-field :auth_id
                             :doc-field  :user}}))

(def CommentsResource
  {:datasource              {:source "comments"}
   :schema                  {:_id     ObjectId
                             :user    ObjectId
                             :company ObjectId
                             :text    s/Str}
   :url                     "comments"
   :resource-methods        #{:get :delete}
   :public-resource-methods #{:get}
   :item-methods            #{:get :put :patch :delete}
   :public-methods          #{:get}
   :auth-strategy           {:type       :field
                             :auth-field :auth_id
                             :doc-field  :user}
   :relations               {:user {:type     :embedded
                                    :path     [:user]
                                    :resource :public-users}}
   :soft-delete             true})

(def hooks
  (compose-hooks auth-field-hooks
                 default-sort-hooks
                 restrict-fields-hooks
                 inject-pagination-hooks))

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

(deftest integration

  (testing "badly formatted query should fail to validate"
    (let [request {}
          query {:toto "c'est l'histoire..."}]
      (is (thrown? ExceptionInfo (validate-query deps CommentsResource request query)))))

  (testing "query for companies should inject auth filter"
    (let [request {:auth {:auth_id "caccccccccccccccccccccc1"}}
          query (validate-query deps CompaniesResource request {:find {}})]
      (is (= "caccccccccccccccccccccc1" (get-in query [:find :_id])))))

  (testing "query for public comments should not inject auth filter"
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query (validate-query deps CommentsResource request {:find {}})]
      (is (nil? (get-in query [:find :_id])))))

  (testing "fetching comments with disabled soft delete should return soft delete"
    (let [query (validate-query deps CommentsResource {} {:find {} :opts {:count? true}})
          fetched (store/fetch-resource deps (dissoc CommentsResource :soft-delete) {} query)]
      (is (= 4 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with enabled soft delete should not return soft delete"
    (let [query (validate-query deps CommentsResource {} {:find {} :opts {:count? true}})
          fetched (store/fetch-resource deps CommentsResource {} query)]
      (is (= 3 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with enabled soft delete but explicitely querying for them should return soft delete"
    (let [query (validate-query deps CommentsResource {} {:find {:_deleted true} :opts {:count? true}})
          fetched (store/fetch-resource deps CommentsResource {} query)]
      (is (= 1 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with pagination"
    (let [query (validate-query deps CommentsResource {} {:find {} :paginate {:per-page 2} :opts {:count? true}})
          fetched (store/fetch-resource deps CommentsResource {} query)]
      (is (= 2 (count (:_documents fetched))))
      (is (= 3 (:_count fetched))))
    (let [query (validate-query deps CommentsResource {} {:find {} :paginate {:per-page 2 :page 2} :opts {:count? true}})
          fetched (store/fetch-resource deps CommentsResource {} query)]
      (is (= 1 (count (:_documents fetched))))
      (is (= 3 (:_count fetched)))))

  (testing "embedding users in comments"
    (let [query (validate-query deps CommentsResource {} {:find {} :relations {:user {}}})
          fetched (store/fetch-resource deps CommentsResource {} query)]
      (doseq [i (take (count (:_documents fetched)) (range))]
        (is (= #{:_id :username}
               (-> fetched (get-in [:_documents i :user]) keys set))))))

  (testing "users fetching should not show password field"
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query (validate-query deps UsersResource request {})
          fetched (store/fetch-resource deps UsersResource {} query)]
      (is (nil?
            (-> fetched :_documents first :password))))
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query (validate-query deps UsersResource request {:fields [:password]})
          fetched (store/fetch-resource deps UsersResource {} query)]
      (is (nil?
            (-> fetched :_documents first :password)))))
  )
