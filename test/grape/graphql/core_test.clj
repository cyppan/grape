(ns grape.graphql.core-test
  (:require [grape.graphql.core :refer :all]
            [clojure.test :refer :all]
            [schema.core :as s]
            [grape.schema :refer :all])
  (:import (graphql Scalars GraphQL)))

(deftest generate-input-schema
  (testing "simple output"
    (let [resources-registry {:users    {:grape.graphql/type 'User
                                         :schema             {:id           s/Str
                                                              :username     s/Str
                                                              (? :nickname) (s/maybe s/Str)
                                                              (? :comments) (read-only [(resource-join :comments :user)])}}
                              :comments {:grape.graphql/type 'Comment
                                         :schema             {:id       s/Str
                                                              (? :user) (s/maybe (resource-embedded :users :user s/Str))}}}
          output-schema (build-schema {:resources-registry resources-registry})
          graphql (GraphQL. output-schema)
          resp (execute {:graphql graphql} {}
                        "query IntrospectionQuery {
                          __type(name: \"Comment\") {
                            name
                            fields {
                              name
                              type {
                                name
                                kind
                                ofType {
                                  name
                                  kind
                                }
                              }
                            }
                          }
                        }")]
      (clojure.pprint/pprint resp)
      )))
