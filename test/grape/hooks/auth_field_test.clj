(ns grape.hooks.auth-field-test
  (:require [clojure.test :refer :all]
            [grape.hooks.auth-field :as auth-field])
  (:import (clojure.lang ExceptionInfo)))

(deftest auth-strategy-with-no-role-field
  (testing "Should not thrown an error"
    (let [hook-pre-read-fn (:pre-read auth-field/hooks)
          resource {:public-operations nil
                    :auth-strategy     {:type       :field
                                        :auth-field :account_id
                                        :doc-field  :_id}}
          request {:auth {:account_id "aaaaaaaaaaaaaaaaaaaaaaaa"}}]
      (is (= {:find {:_id "aaaaaaaaaaaaaaaaaaaaaaaa"}}
            (hook-pre-read-fn nil resource request nil))))))

(deftest auth-strategy-with-role-field
  (testing "Should thrown an error because bad role"
    (let [hook-pre-read-fn (:pre-read auth-field/hooks)
          resource {:public-operations nil
                    :auth-strategy     {:type       :field
                                        :auth-field :account_id
                                        :doc-field  :_id
                                        :has-role (fn [role] (= role "foo"))}}
          request {:auth {:account_id "aaaaaaaaaaaaaaaaaaaaaaaa"
                          :roles ["baz"]}}]
      (is (thrown? ExceptionInfo (hook-pre-read-fn nil resource request nil))))))
