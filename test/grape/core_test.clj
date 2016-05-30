(ns grape.core-test
  (:require [clojure.test :refer :all]
            [grape.core :refer :all]
            [grape.store :as store]
            [grape.query :refer [validate-query]]
            [grape.system-test]
            [grape.system-test :refer :all])
  (:import (clojure.lang ExceptionInfo)))

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
          fetched (fetch-resource deps (dissoc CommentsResource :soft-delete) {} query)]
      (is (= 4 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with enabled soft delete should not return soft delete"
    (let [query (validate-query deps CommentsResource {} {:find {} :opts {:count? true}})
          fetched (fetch-resource deps CommentsResource {} query)]
      (is (= 3 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with enabled soft delete but explicitely querying for them should return soft delete"
    (let [query (validate-query deps CommentsResource {} {:find {:_deleted true} :opts {:count? true}})
          fetched (fetch-resource deps CommentsResource {} query)]
      (is (= 1 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with pagination"
    (let [query (validate-query deps CommentsResource {} {:find {} :paginate {:per-page 2} :opts {:count? true}})
          fetched (fetch-resource deps CommentsResource {} query)]
      (is (= 2 (count (:_documents fetched))))
      (is (= 3 (:_count fetched))))
    (let [query (validate-query deps CommentsResource {} {:find {} :paginate {:per-page 2 :page 2} :opts {:count? true}})
          fetched (fetch-resource deps CommentsResource {} query)]
      (is (= 1 (count (:_documents fetched))))
      (is (= 3 (:_count fetched)))))

  (testing "embedding users in comments"
    (let [query (validate-query deps CommentsResource {} {:find {} :relations {:user {}}})
          fetched (fetch-resource deps CommentsResource {} query)]
      (doseq [i (take (count (:_documents fetched)) (range))]
        (is (= #{:_id :username}
               (-> fetched (get-in [:_documents i :user]) keys set))))))

  (testing "users fetching should not show password field"
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query (validate-query deps UsersResource request {})
          fetched (fetch-resource deps UsersResource {} query)]
      (is (nil?
            (-> fetched :_documents first :password))))
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query (validate-query deps UsersResource request {:fields [:password]})
          fetched (fetch-resource deps UsersResource {} query)]
      (is (nil?
            (-> fetched :_documents first :password)))))
  )
