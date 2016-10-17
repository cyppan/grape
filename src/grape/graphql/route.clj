(ns grape.graphql.route
  (:require [cheshire.core :refer :all]
            [grape.graphql.core :refer [execute]]))

(defn relay-handler [deps request]
  (let [query (get-in request [:body :query])
        variables (get-in request [:body :variables])
        result (execute deps request
                        query
                        variables)]
    {:status 200
     :body   result}))
