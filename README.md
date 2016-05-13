# Grappe

A Clojure library designed to build data-first APIs.
This library is a work in progress.

## Usage

Everything is built around **resources**

A resource corresponds generally to a MongoDB collection, it's a dictionary
 configuration to expose your collection safely to the external world, providing:
* One or more Rest endpoint for your resource with a JSON DSL for fetching
* Authorization
* Soft Delete
* Function Hooks
* Relations (automatic fetching of related resources)
* Schema validations (using the powerful Prismatic Schema lib)

This library is highly functional and you can easily add features around your resources
using hooks on:
* pre-fetch: to map the query
* post-fetch: to modify the fetched documents
* ...


**Example:**

```clojure
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
                      :email    s/Str}
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
```


## What's Implemented for the moment

* REST Support with only GET supported
* MongoDB backend
* One datasource for all resources


## Roadmap

* Creation, Edition and Deletion of resources
* GraphQL + Relay support
* Falcor support
* Support different datasources by resource
* Real time subscriptions (probably using MongoDB tailable cursors + core.async channels with SSE)


## License

Distributed under the Eclipse Public License 1.0 (EPL-1.0)
