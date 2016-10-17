(ns grape.core-test
  (:require [clojure.test :refer :all]
            [grape.core :refer :all]
            [grape.query :refer [validate-query]]
            [grape.fixtures.comments :refer :all]
            [grape.test-utils :refer :all])
  (:import (clojure.lang ExceptionInfo)
           (org.bson.types ObjectId)))

;; TODO test relations not existing
;; TODO test pagination null

(deftest integration

  (testing "badly formatted query should fail to validate"
    (let [request {}
          query {:toto "c'est l'histoire..."}]
      (is (thrown? ExceptionInfo (validate-query deps CommentsResource request query {:recur? true})))))

  (testing "query for public comments should not inject auth filter"
    (with-test-system
      deps
      (fn [system]
        (let [request {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}}
              fetched (read-resource system CommentsResource request {})]
          (is (nil? (get-in fetched [:_query :find :_id])))))))

  (testing "fetching comments with disabled soft delete should return soft delete"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [query {:find {} :opts {:count? true}}
              fetched (read-resource system (dissoc CommentsResource :soft-delete) {} query)]
          (is (= 4 (count (:_documents fetched)) (:_count fetched)))))))

  (testing "fetching comments with enabled soft delete should not return soft delete"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [query {:find {} :opts {:count? true}}
              fetched (read-resource system CommentsResource {} query)]
          (is (= 3 (count (:_documents fetched)) (:_count fetched)))))))

  (testing "fetching comments with enabled soft delete but explicitely querying for them should return soft delete"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [query {:find {:_deleted true} :opts {:count? true}}
              fetched (read-resource system CommentsResource {} query)]
          (is (= 1 (count (:_documents fetched)) (:_count fetched)))))))

  (testing "fetching comments with pagination"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [query {:find {} :paginate {:per-page 2} :opts {:count? true}}
              fetched (read-resource system CommentsResource {} query)]
          (is (= 2 (count (:_documents fetched))))
          (is (= 3 (:_count fetched))))
        (let [query {:find {} :paginate {:per-page 2 :page 2} :opts {:count? true}}
              fetched (read-resource system CommentsResource {} query)]
          (is (= 1 (count (:_documents fetched))))
          (is (= 3 (:_count fetched)))))))

  (testing "users fetching should not show password field"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [request {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}}
              query {}
              fetched (read-resource system UsersResource request query)]
          (is (nil?
                (-> fetched :_documents first :password))))
        (let [request {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}}
              query {:fields [:password :username]}
              fetched (read-resource system UsersResource request query)]
          (is (nil?
                (-> fetched :_documents first :password)))))))

  (testing "embedding users in comments"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [fetched (read-resource system CommentsResource {} {:find {} :relations {:user {}}})]
          (doseq [i (take (count (:_documents fetched)) (range))]
            (is (every? #{:_id :username :friends :godchild}
                        (-> fetched (get-in [:_documents i :user]) keys set))))))))

  (testing "embedding comments in user"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [fetched (read-resource system UsersResource
                                     {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}}
                                     {:find {:_id "aaaaaaaaaaaaaaaaaaaaaaa1"} :relations {(keyword "comments.[]") {}}})]
          (doseq [i (take (count (:_documents fetched)) (range))]
            (is (= #{:_id :user :text}
                   (-> fetched (get-in [:_documents i :comments]) first keys set))))))))

  (testing "embedding replies in comment"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [inserted1 (create-resource system CommentsResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:text "reply 1" :parent "ccccccccccccccccccccccc1"})
              inserted2 (create-resource system CommentsResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:text "reply 2" :parent "ccccccccccccccccccccccc1"})
              inserted3 (create-resource system CommentsResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:text "reply 3" :parent "ccccccccccccccccccccccc1"})
              inserted4 (create-resource system CommentsResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:text "reply 4" :parent "ccccccccccccccccccccccc1"})
              fetched (read-resource system CommentsResource
                                     {}
                                     {:find {:_id "ccccccccccccccccccccccc1"} :relations {(keyword "last_replies.[]") {}}})]
          (doseq [i (take (count (:_documents fetched)) (range))
                  :when (> (inc i) 1)]
            (is (= {:text   (str "reply " (inc i))
                    :user   (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")
                    :parent (ObjectId. "ccccccccccccccccccccccc1")}
                   (-> fetched (get-in [:_documents i]) (select-keys [:text :user :parent])))))))))

  (testing "embedding likes in comment"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [_ (create-resource system LikesResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:comment "ccccccccccccccccccccccc1"})
              _ (create-resource system LikesResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2")}} {:comment "ccccccccccccccccccccccc1"})
              fetched (read-resource system CommentsResource
                                     {}
                                     {:find {:_id "ccccccccccccccccccccccc1"} :relations {(keyword "likes.[]") {}}})]
          (is (= {:user    (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")
                  :comment (ObjectId. "ccccccccccccccccccccccc1")}
                 (-> fetched (get-in [:_documents 0 :likes 0]) (select-keys [:comment :user]))))))))

  (testing "embedding likes in comment"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [_ (create-resource system LikesResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:comment "ccccccccccccccccccccccc1"})
              _ (create-resource system LikesResource {:auth {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}} {:comment "ccccccccccccccccccccccc1"})
              fetched (read-resource system CommentsResource
                                     {}
                                     {:find {:_id "ccccccccccccccccccccccc1"} :relations {(keyword "likes.[]") {}}})]
          (is (= {:user    (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")
                  :comment (ObjectId. "ccccccccccccccccccccccc1")}
                 (-> fetched (get-in [:_documents 0 :likes 0]) (select-keys [:comment :user]))))))))
  )
