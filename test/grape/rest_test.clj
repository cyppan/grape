(ns grape.rest-test
  (:require [clojure.test :refer :all]
            [grape.rest.parser :refer :all]
            [grape.rest.route :refer :all]
            [cheshire.core :refer :all]
            [bidi.bidi :refer :all]
            [grape.system-test :refer :all]))

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

(deftest route-handlers
  (testing "get resource handler"
    (let [resource {:url              "myresource"
                    :resource-methods #{:get}
                    :item-methods     #{:get}}
          routes ["/" (build-resources-routes {:resources-registry {:myresource resource}})]
          resource-match (match-route routes "/myresource")
          item-match (match-route routes "/myresource/1234")]
      (is (nil? (match-route routes "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "1234" (get-in item-match [:route-params :_id])))))

  (testing "get resource handler with extra endpoints"
    (let [resource {:url              "myresource"
                    :resource-methods #{:get}
                    :item-methods     #{:get}
                    :extra-endpoints  [[["extra/" :param] identity]
                                       ["other" identity]]}
          routes ["/" (build-resource-routes {} resource)]
          resource-match (match-route routes "/myresource")
          item-match (match-route routes "/myresource/1234")
          extra-match (match-route routes "/extra/toto")
          other-match (match-route routes "/other")]
      (is (nil? (match-route routes "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "1234" (get-in item-match [:route-params :_id])))
      (is (= "toto" (get-in extra-match [:route-params :param])))
      (is (not (nil? (:handler other-match)))))))

(deftest get-resource
  (testing "get public users"
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/public_users")
          handler (:handler match)
          request {:query-params {"query" ""} :request-method :get}
          resp (handler request)]
      (is (= 3 (:_count resp)))
      (is (= #{:_id :username} (->> (:_items resp)
                                    first
                                    keys
                                    (into #{}))))
      (is (= #{"user 1" "user 2" "user 3"} (->> (:_items resp)
                                                (map :username)
                                                (into #{})))))))

