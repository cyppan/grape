(ns grappe.rest-test
  (:require [clojure.test :refer :all]
            [grappe.rest.parser :refer :all]
            [grappe.rest.route :refer :all]
            [cheshire.core :refer :all]
            [bidi.bidi :refer :all]))

(deftest eve-like-query

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

  (testing "Grappe query DSL"
    (let [query {:find      {:name {:$regex "toto"}}
                 :fields    ["name"]
                 :paginate  {:page 1 :per-page 50}
                 :sort      {:_created -1}
                 :relations {:myrelation {}}}
          request {:query-params {"query" (generate-string query)}}]
      (is (= (parse-query request) query)))))

(deftest route-handlers
  (testing "get resource handler"
    (let [resource {:url              "myresource"
                    :resource-methods #{:get}
                    :item-methods     #{:get}}
          resource-match (match-route (build-resource-routes {} resource) "/myresource")
          item-match (match-route (build-resource-routes {} resource) "/myresource/1234")]
      (is (nil? (match-route (build-resource-routes {} resource) "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "1234" (get-in item-match [:route-params :_id])))))

  (testing "get resource handler with extra endpoints"
    (let [resource {:url              "myresource"
                    :resource-methods #{:get}
                    :item-methods     #{:get}
                    :extra-endpoints  [[["extra/" :param] identity]
                                       ["other" identity]]}
          routes (build-resource-routes {} resource)
          resource-match (match-route routes "/myresource")
          item-match (match-route routes "/myresource/1234")
          extra-match (match-route routes "/extra/toto")
          other-match (match-route routes "/other")]
      (is (nil? (match-route routes "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "1234" (get-in item-match [:route-params :_id])))
      (is (= "toto" (get-in extra-match [:route-params :param])))
      (is (not (nil? (:handler other-match))))))
  )

