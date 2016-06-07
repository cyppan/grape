(ns commentthreadcomponent.resources
  (:require [grape.schema :refer :all]))

(def UsersResource
  {:datasource        {:source "users"}
   :schema            {(? :_id)      ObjectId
                       :username     #"^[A-Za-z0-9_ ]{2,25}$"
                       (? :website)  (maybe UrlField)
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
                       :user         (ResourceField ObjectId :users)
                       :text         Str
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
                       :user         (ResourceField ObjectId :public-users)
                       :comment      (ResourceField ObjectId :comments)
                       (? :_created) (read-only DateTime)
                       (? :_updated) (read-only DateTime)}
   :url               "likes"
   :operations        #{:create :read :delete}
   :public-operations #{:read}
   :auth-strategy     {:type       :field
                       :auth-field :user-id
                       :doc-field  :user}})

(def resources-registry
  {:users        UsersResource
   :public-users PublicUsersResource
   :comments     CommentsResource
   :likes        LikesResource})