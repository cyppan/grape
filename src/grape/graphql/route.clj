(ns grape.graphql.route
  (:require [cheshire.core :refer :all]
            [grape.graphql.core :refer [execute]]))

(defn relay-handler [deps request]
  (let [resp (execute deps {}
                      "query UserQuery {
                        Users(id:\"aaaaaaaaaaaaaaaaaaaaaaa1\") {
                          username
                          email
                        }
                      }"
                      {})]
    (clojure.pprint/pprint resp)))
