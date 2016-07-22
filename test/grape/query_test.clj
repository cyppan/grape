(ns grape.query-test
  (:require [clojure.test :refer :all]
            [grape.fixtures :refer [deps]]
            [grape.query :refer :all]
            [cheshire.core :refer :all])
  (:import (clojure.lang ExceptionInfo)))

(deftest validate-query-test
  (testing "invalid mongodb operator"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:find {:text {:$$regex ""}}}]
      (is (thrown-with-msg? ExceptionInfo #"find invalid"
                            (validate-query deps resource {} query {})))))

  (testing "unknown schema key"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:find {:test {:$regex ""}}}]
      (is (thrown-with-msg? ExceptionInfo #"find invalid"
                            (validate-query deps resource {} query {})))))

  (testing "find valid"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:find {:text {:$regex ""}}}]
      (is (= query (select-keys (validate-query deps resource {} query {}) [:find])))))

  (testing "ignore not existing relations"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {:unknown {}}}]
      (is (nil? (:relations (validate-query deps resource {} query {}))))))

  (testing "relation embed one"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {:parent {}}}]
      (is (= :parent (first (first (:relations (validate-query deps resource {} query {}))))))))

  (testing "relation embed many - mongo syntax"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {:last_replies {}}}]
      (is (= (keyword "last_replies.[]") (first (first (:relations (validate-query deps resource {} query {}))))))))

  (testing "relation embed many - array syntax"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {(keyword "last_replies.[]") {}}}]
      (is (= (keyword "last_replies.[]") (first (first (:relations (validate-query deps resource {} query {}))))))))

  (testing "relation join many - mongo syntax"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {:likes {}}}]
      (is (= (keyword "likes.[]") (first (first (:relations (validate-query deps resource {} query {}))))))))

  (testing "relation embed many - array syntax"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {(keyword "likes.[]") {}}}]
      (is (= (keyword "likes.[]") (first (first (:relations (validate-query deps resource {} query {}))))))))

  (testing "recursively validate - invalid"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {:parent {:find {:test {:$regex ""}}}}}]
      (is (thrown-with-msg? ExceptionInfo #"find invalid" (validate-query deps resource {} query {:recur? true})))))

  (testing "recursively validate query - valid"
    (let [resource (get-in deps [:resources-registry :comments] )
          query {:relations {:parent {:find {:text {:$regex ""}}}}}
          {{{:keys [find relations]} :parent} :relations} (validate-query deps resource {} query {:recur? true})]
      (is (= {:text {:$regex ""}} find))
      (is (nil? relations))))
  )
