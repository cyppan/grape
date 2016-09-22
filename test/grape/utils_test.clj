(ns grape.utils-test
  (:require [clojure.test :refer :all]
            [grape.utils :refer :all]
            [schema.core :as s]
            [grape.schema :refer :all]
            [com.rpl.specter :refer :all])
  (:import (org.bson.types ObjectId)))

(deftest deep-merge-test
  (testing "nil"
    (let [map1 nil
          map2 {}
          merged (deep-merge map1 map2)]
      (is (= merged {})))
    (let [map1 {}
          map2 nil
          merged (deep-merge map1 map2)]
      (is (= merged nil))))
  (testing "one level"
    (let [map1 {:one "one"
                :two "two"}
          map2 {:three "three"}
          merged (deep-merge map1 map2)]
      (is (= merged {:one "one" :two "two" :three "three"}))))
  (testing "one level override"
    (let [map1 {:one "one"
                :two "two"}
          map2 {:two   "two overriden"
                :three "three"}
          merged (deep-merge map1 map2)]
      (is (= merged {:one "one" :two "two overriden" :three "three"}))))
  (testing "two levels"
    (let [map1 {:one "one" :embedded {:one "one" :two "two"}}
          map2 {:embedded {:two "two overriden" :three "three"}}
          merged (deep-merge map1 map2)]
      (is (= merged {:one "one" :embedded {:one "one" :two "two overriden" :three "three"}})))))

(deftest flatten-structure-test
  (testing "nil"
    (is (= (flatten-structure nil) nil)))
  (testing "empty"
    (is (= (flatten-structure {}) '())))
  (testing "one level"
    (is (= (flatten-structure {:one "one" :two "two"})
           '([[:one] "one"] [[:two] "two"]))))
  (testing "two levels"
    (is (= (flatten-structure {:one "one" :embedded {:one "one" :two "two"}})
           '([[:one] "one"] [[:embedded :one] "one"] [[:embedded :two] "two"]))))
  (testing "two levels with array"
    (is (= (flatten-structure {:one "one" :seq [1 2]})
           '([[:one] "one"] [[:seq []] 1] [[:seq []] 2]))))
  (testing "two levels with array of objects"
    (is (= (flatten-structure {:one "one" :seq [{:one "one"} {:two "two"}]})
           '([[:one] "one"] [[:seq [] :one] "one"] [[:seq [] :two] "two"])))))

(deftest expand-keyseqs-test
  (testing "with sequence expanded"
    (let [schema {:one      s/Str
                  :embedded [{:one s/Int :two s/Int}]}]
      (is (= (get-schema-keyseqs schema)
             #{[:one] [:embedded] [:embedded []] [:embedded [] :one] [:embedded [] :two]})))))

(deftest walk-structure-test
  (testing "map values"
    (let [schema {:one "one" :seq [{:un "un"}] :embedded {:uno "uno"}}
          mapped (walk-structure schema identity (constantly 1))]
      (is (= mapped {:one 1 :seq [{:un 1}] :embedded {:uno 1}}))))
  (testing "map keys"
    (let [schema {:one "one" :seq [{:un "un"}] :embedded {:uno "uno"}}
          mapped (walk-structure schema (comp name s/explicit-schema-key) identity)]
      (is (= mapped {"one" "one" "seq" [{"un" "un"}] "embedded" {"uno" "uno"}})))))

(deftest walk-schema-test
  (testing "map schema keys"
    (let [schema {:one s/Str :seq [{:un s/Str}] :embedded {:uno s/Str}}
          mapped (walk-schema schema (comp ? s/explicit-schema-key) (fn [path value] value))]
      (is (= {(? :one)      s/Str
              (? :seq)      [{(? :un) s/Str}]
              (? :embedded) {(? :uno) s/Str}}
             mapped))))
  (testing "map schema keys"
    (let [schema {(? :one) s/Str :seq [{:un s/Str}] :embedded {:uno s/Str}}
          mapped (walk-schema schema identity (constantly 1))]
      (is (= {(? :one) 1 :seq [{:un 1}] :embedded {:uno 1}} mapped)))))

(deftest get-schema-keyseqs-test
  (testing "get keyseqs"
    (let [schema {:one s/Str :seq [{:un s/Str}] :embedded {:uno s/Str}}
          keyseqs (get-schema-keyseqs schema)]
      (is (= #{[:one] [:seq] [:seq []] [:seq [] :un] [:embedded] [:embedded :uno]} keyseqs)))))

(deftest get-schema-relations-test
  (testing "no relations"
    (let [schema {:one s/Str}
          relations (get-schema-relations schema)]
      (is (= relations {}))))
  (testing "root relations"
    (let [spec-one {:type     :embedded
                    :resource :sample}
          spec-two {:type     :join
                    :resource :other
                    :field    :field}
          schema {:one (resource-embedded :sample ObjectId)
                  (? :two) (read-only [(resource-join :other :field)])}
          relations (get-schema-relations schema)]
      (is (= relations {[:one]    spec-one
                        [:two ALL] spec-two}))))
  (testing "array relation"
    (let [spec {:type     :embedded
                :resource :sample}
          schema {:seq [{:field (resource-embedded :sample ObjectId)}]}
          relations (get-schema-relations schema)]
      (is (= relations {[:seq ALL :field] spec}))))
  (testing "join in a root array is not supported"
    (let [spec {:type     :join
                :resource :other
                :field    :field}
          schema {:seq [{:res (resource-join :other :field)}]}]
      (is (thrown-with-msg? AssertionError
                            #"schema error"
                            (get-schema-relations schema)))))
  (testing "join in a direct array is supported"
    (let [spec {:type     :join
                :resource :other
                :field    :field}
          schema {:seq [(resource-join :other :field)]}
          relations (get-schema-relations schema)]
      (is (= relations {[:seq ALL] spec})))))
