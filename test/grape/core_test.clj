(ns grape.core-test
  (:require [clojure.test :refer :all]
            [grape.core :refer :all]
            [grape.store :as store]
            [grape.query :refer [validate-query]]
            [grape.system-test]
            [grape.system-test :refer :all])
  (:import (clojure.lang ExceptionInfo)))

;; TODO test relations not existing
;; TODO test pagination null
;; TODO check why fields are not injected

(deftest integration

  (testing "badly formatted query should fail to validate"
    (let [request {}
          query {:toto "c'est l'histoire..."}]
      (is (thrown? ExceptionInfo (validate-query deps CommentsResource request query {:recur? true})))))

  (testing "fetching companies should inject auth filter"
    (let [request {:auth {:auth_id "caccccccccccccccccccccc1"}}
          fetched (read-resource deps CompaniesResource request {})]
      (is (= "caccccccccccccccccccccc1" (get-in fetched [:_query :find :_id])))))

  (testing "query for public comments should not inject auth filter"
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          fetched (read-resource deps CommentsResource request {})]
      (is (nil? (get-in fetched [:_query :find :_id])))))

  (testing "fetching comments with disabled soft delete should return soft delete"
    (load-fixtures)
    (let [query {:find {} :opts {:count? true}}
          fetched (read-resource deps (dissoc CommentsResource :soft-delete) {} query)]
      (is (= 4 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with enabled soft delete should not return soft delete"
    (load-fixtures)
    (let [query {:find {} :opts {:count? true}}
          fetched (read-resource deps CommentsResource {} query)]
      (is (= 3 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with enabled soft delete but explicitely querying for them should return soft delete"
    (load-fixtures)
    (let [query {:find {:_deleted true} :opts {:count? true}}
          fetched (read-resource deps CommentsResource {} query)]
      (is (= 1 (count (:_documents fetched)) (:_count fetched)))))

  (testing "fetching comments with pagination"
    (load-fixtures)
    (let [query {:find {} :paginate {:per-page 2} :opts {:count? true}}
          fetched (read-resource deps CommentsResource {} query)]
      (is (= 2 (count (:_documents fetched))))
      (is (= 3 (:_count fetched))))
    (let [query {:find {} :paginate {:per-page 2 :page 2} :opts {:count? true}}
          fetched (read-resource deps CommentsResource {} query)]
      (is (= 1 (count (:_documents fetched))))
      (is (= 3 (:_count fetched)))))

  (testing "embedding users in comments"
    (load-fixtures)
    (let [fetched (read-resource deps CommentsResource {} {:find {} :relations {:user {}}})]
      (doseq [i (take (count (:_documents fetched)) (range))]
        (is (= #{:_id :username}
               (-> fetched (get-in [:_documents i :user]) keys set))))))

  (testing "users fetching should not show password field"
    (load-fixtures)
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query {}
          fetched (read-resource deps UsersResource request query)]
      (is (nil?
            (-> fetched :_documents first :password))))
    (let [request {:auth {:auth_id "aaaaaaaaaaaaaaaaaaaaaaa1"}}
          query {:fields [:password :username]}
          fetched (read-resource deps UsersResource request query)]
      (is (nil?
            (-> fetched :_documents first :password)))))
  )
