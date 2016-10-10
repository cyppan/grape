(ns grape.graphql.comments-test
  (:require [clojure.test :refer :all]
            [cheshire.core :refer :all]
            [grape.test-utils :refer :all]
            [grape.store :refer :all]
            [grape.fixtures.comments :refer :all]
            [grape.graphql.core :refer [execute]])
  (:import (org.bson.types ObjectId)))

(deftest graphql-item
  (testing ""))

(deftest graphql-list
  (testing ""))

(deftest relay-connection
  (testing "Simple CommentList query all"
    (with-test-system
      (assoc deps :store (new-mongo-datasource {:uri "mongodb://localhost:27017/test"}))
      (fn [system]
        (load-fixtures)
        (let [resp (execute system {}
                            "query CommentsListQuery {
                               CommentsList {
                                 edges {
                                   cursor
                                   node {
                                     id
                                     text
                                   }
                                 }
                                 pageInfo {
                                   hasNextPage
                                 }
                               }
                             }"
                            {})]
          (is (= (get-in resp ["CommentsList" "pageInfo" "hasNextPage"]) false))
          (is (= (into #{} (map #(get-in % ["node" "id"]) (get-in resp ["CommentsList" "edges"])))
                 #{(ObjectId. "ccccccccccccccccccccccc1") (ObjectId. "ccccccccccccccccccccccc2") (ObjectId. "ccccccccccccccccccccccc3")}))
          (is (= (into #{} (map #(get-in % ["node" "text"]) (get-in resp ["CommentsList" "edges"])))
                 #{"this company is cool" "love you guys :D" "spam"}))))))

  (testing "Simple CommentList query paginate"
    (with-test-system
      (assoc deps :store (new-mongo-datasource {:uri "mongodb://localhost:27017/test"}))
      (fn [system]
        (load-fixtures)
        (let [resp (execute system {}
                            "query CommentsListQuery {
                               CommentsList(first: 2) {
                                 edges {
                                   cursor
                                   node {
                                     id
                                     text
                                   }
                                 }
                                 pageInfo {
                                   hasNextPage
                                 }
                               }
                             }"
                            {})]
          (is (= (get-in resp ["CommentsList" "pageInfo" "hasNextPage"]) true))
          (is (= (into #{} (map #(get-in % ["node" "id"]) (get-in resp ["CommentsList" "edges"])))
                 #{(ObjectId. "ccccccccccccccccccccccc1") (ObjectId. "ccccccccccccccccccccccc2")}))
          (is (= (into #{} (map #(get-in % ["node" "text"]) (get-in resp ["CommentsList" "edges"])))
                 #{"this company is cool" "love you guys :D"}))
          (let [cursor (get-in resp ["CommentsList" "edges" 1 "cursor"])
                resp (execute system {}
                              (str "query CommentsListQuery {
                                 CommentsList(after: \"" cursor "\") {
                                   edges {
                                     cursor
                                     node {
                                       id
                                       text
                                     }
                                   }
                                   pageInfo {
                                     hasNextPage
                                   }
                                 }
                               }")
                              {})]
            (is (= (get-in resp ["CommentsList" "pageInfo" "hasNextPage"]) false))
            (is (= (into #{} (map #(get-in % ["node" "id"]) (get-in resp ["CommentsList" "edges"])))
                   #{(ObjectId. "ccccccccccccccccccccccc3")})))))))
  ;
  ;(testing "Simple CommentList query paginate - page 1"
  ;  (with-test-system
  ;    (assoc deps :store (new-mongo-datasource {:uri "mongodb://localhost:27017/test"}))
  ;    (fn [system]
  ;      (load-fixtures)
  ;      (let [resp (execute system {}
  ;                          "query CommentsListQuery {
  ;                             CommentsList(first: 2) {
  ;                               edges {
  ;                                 cursor
  ;                                 node {
  ;                                   id
  ;                                   text
  ;                                 }
  ;                               }
  ;                               pageInfo {
  ;                                 hasNextPage
  ;                               }
  ;                             }
  ;                           }"
  ;                          {})]
  ;        (is (= (get-in resp ["pageInfo" "hasNextPage"]) false))
  ;        (is (= (get-in resp ["CommentsList" "edges" 0 "node" "id"]) (ObjectId. "ccccccccccccccccccccccc1")))
  ;        (is false)))))


  ;(testing "CommentList query"
  ;  (with-test-system
  ;    (assoc deps :store (new-mongo-datasource {:uri "mongodb://localhost:27017/test"}))
  ;    (fn [system]
  ;      (load-fixtures)
  ;      (let [resp (execute system {}
  ;                          "query CommentsListQuery($first: Int, $sort: String, $find: String) {
  ;                             CommentsList(first: $first, sort: $sort, find: $find) {
  ;                               edges {
  ;                                 cursor
  ;                                 node {
  ;                                   id
  ;                                   text
  ;                                   user {
  ;                                     username
  ;                                     friends {
  ;                                       id
  ;                                       username
  ;                                     }
  ;                                     godfather {
  ;                                       id
  ;                                       username
  ;                                     }
  ;                                   }
  ;                                   likes {
  ;                                     user {
  ;                                       username
  ;                                     }
  ;                                   }
  ;                                 }
  ;                               }
  ;                               pageInfo {
  ;                                 hasNextPage
  ;                               }
  ;                             }
  ;                           }"
  ;                          {"find" "{}" "first" (int 1)})]
  ;        (clojure.pprint/pprint resp)
  ;        (is false)
  ;        ))))
  )

