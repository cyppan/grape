(ns grape.rest.parser-test
  (:require [clojure.test :refer :all]
            [grape.rest.parser :refer :all]
            [grape.rest.route :refer :all]
            [cheshire.core :refer :all]
            [bidi.bidi :refer :all]
            [grape.fixtures :refer :all]
            [slingshot.slingshot :refer [throw+ try+]]))

(deftest query-parser
  (testing "Eve query DSL compatibility"
    (let [where "{\"name\":{\"$regex\":\"toto\"}}"
          projection "{\"name\":1}"
          embedded "{\"myrelation\":1}"
          sort "-_created"
          page "1"
          max_results "50"
          request {:query-params {"where"       where
                                  "projection"  projection
                                  "embedded"    embedded
                                  "page"        page
                                  "max_results" max_results
                                  "sort"        sort}}
          query (parse-query request)]
      (is (= query {:find      {:name {:$regex "toto"}}
                    :fields    ["name"]
                    :paginate  {:page 1 :per-page 50}
                    :sort      {:_created -1}
                    :relations {:myrelation {}}}))))

  (testing "Grape query DSL"
    (let [query {:find      {:name {:$regex "toto"}}
                 :fields    ["name"]
                 :paginate  {:page 1 :per-page 50}
                 :sort      {:_created -1}
                 :relations {:myrelation {}}}
          request {:query-params {"query" (generate-string query)}}]
      (is (= (parse-query request) query)))))
