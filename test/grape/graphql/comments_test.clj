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

  (testing "Simple CommentList query limit at document count"
    (with-test-system
      (assoc deps :store (new-mongo-datasource {:uri "mongodb://localhost:27017/test"}))
      (fn [system]
        (load-fixtures)
        (let [resp (execute system {}
                            "query CommentsListQuery {
                               CommentsList(first: 3) {
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
          (is (= (get-in resp ["CommentsList" "pageInfo" "hasNextPage"]) false))))))

  (testing "CommentList embed everything"
    (with-test-system
      (assoc deps :store (new-mongo-datasource {:uri "mongodb://localhost:27017/test"}))
      (fn [system]
        (load-fixtures)
        (let [resp (execute system {}
                            "query CommentsListQuery($first: Int, $sort: String, $find: String, $after: String) {
                              CommentsList(first: $first, sort: $sort, find: $find, after: $after) {
                                edges {
                                  cursor
                                  node {
                                    id
                                    text
                                    user {
                                      username
                                      friends {
                                        id
                                        username
                                      }
                                      godfather {
                                        id
                                        username
                                      }
                                    }
                                    likes {
                                      user {
                                        username
                                      }
                                    }
                                  }
                                }
                                pageInfo {
                                  hasNextPage
                                }
                              }
                            }"
                            {"first" (int 1)
                             "after" "1"})]
          (is (= 1 (clojure.core/count (get-in resp ["CommentsList" "edges"]))))
          (is (= (get-in resp ["CommentsList" "pageInfo" "hasNextPage"]) true))
          (let [node (get-in resp ["CommentsList" "edges" 0 "node"])]
            (is (= (get node "id") (ObjectId. "ccccccccccccccccccccccc2")))
            (is (= (get node "text") "love you guys :D"))
            (is (= (get-in node ["user" "username"]) "user 2"))
            (is (= (get-in node ["user" "friends" 0 "username"]) "user 1"))
            (is (= (get-in node ["user" "godfather" "id"]) (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")))
            (is (= (get-in node ["user" "godfather" "username"]) "user 1"))
            (is (= (get-in node ["likes" 0 "user" "username"]) "user 1"))))))))

