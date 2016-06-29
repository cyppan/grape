(ns grape.utils-test
  (:require [clojure.test :refer :all]
            [grape.utils :refer :all]
            [grape.schema :refer :all]
            [schema.core :as s]))

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
    (let [schema {:one      Str
                  :embedded [{:one Int :two Int}]}]
      (is (= (expand-keyseqs (get-schema-keyseqs schema) false)
             #{[:one] [:embedded] [:embedded []] [:embedded [] :one] [:embedded [] :two]}))))
  (testing "with sequence unexpanded"
    (let [schema {:one      Str
                  :embedded [{:one Int :two Int}]}]
      (is (= (expand-keyseqs (get-schema-keyseqs schema) true)
             #{[:one] [:embedded] [:embedded :one] [:embedded :two]})))))

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
    (let [schema {:one Str :seq [{:un Str}] :embedded {:uno Str}}
          mapped (walk-schema schema #(s/optional-key (s/explicit-schema-key %)) identity)]
      (is (= {(s/optional-key :one)      Str
              (s/optional-key :seq)      [{(s/optional-key :un) Str}]
              (s/optional-key :embedded) {(s/optional-key :uno) Str}}
             mapped))))
  (testing "map schema keys"
    (let [schema {(s/optional-key :one) Str :seq [{:un Str}] :embedded {:uno Str}}
          mapped (walk-schema schema identity (constantly 1))]
      (is (= {(s/optional-key :one) 1 :seq [{:un 1}] :embedded {:uno 1}} mapped)))))

(deftest get-schema-keyseqs-test
  (testing "get keyseqs"
    (let [schema {:one Str :seq [{:un Str}] :embedded {:uno Str}}
          keyseqs (get-schema-keyseqs schema)]
      (is (= '([:one] [:seq [] :un] [:embedded :uno]) keyseqs)))))

(deftest get-schema-relations-test
  (testing "no relations"
    (let [schema {:one Str}
          relations (get-schema-relations schema)]
      (is (= relations {}))))
  (testing "root relations"
    (let [spec-one {:type     :embedded
                    :resource :sample}
          spec-two {:type     :join
                    :resource :other
                    :field    :field}
          schema {:one
                  (vary-meta Str
                             merge {:grape/relation-spec
                                    spec-one})
                  (s/optional-key :two)
                  (read-only [(vary-meta s/Any
                                         merge {:grape/relation-spec
                                                spec-two})])}
          relations (get-schema-relations schema)]
      (is (= relations {[:one] (assoc spec-one :path [:one])
                        [:two []] (assoc spec-two :path [:two])}))))
  (testing "array relation"
    (let [spec {:type     :embedded
                :resource :sample}
          schema {:seq [{:field (vary-meta ObjectId
                                           merge {:grape/relation-spec
                                                  spec})}]}
          relations (get-schema-relations schema)]
      (is (= relations {[:seq [] :field] (assoc spec :path [:seq :field])}))))
  (testing "join in a root array is not supported"
    (let [spec {:type     :join
                :resource :other
                :field    :field}
          schema {:seq [{:res (vary-meta s/Any
                                         merge {:grape/relation-spec
                                                spec})}]}]
      (is (thrown-with-msg? AssertionError
                            #"schema error"
                            (get-schema-relations schema)))))
  (testing "join in a direct array is supported"
    (let [spec {:type     :join
                :resource :other
                :field    :field}
          schema {:seq [(vary-meta s/Any
                                   merge {:grape/relation-spec
                                          spec})]}
          relations (get-schema-relations schema)]
      (is (= relations {[:seq []] (assoc spec :path [:seq])})))))
