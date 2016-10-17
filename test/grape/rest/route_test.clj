(ns grape.rest.route-test
  (:require [clojure.test :refer :all]
            [grape.rest.parser :refer :all]
            [grape.rest.route :refer :all]
            [cheshire.core :refer :all]
            [bidi.bidi :refer :all]
            [grape.fixtures.comments :refer :all]
            [slingshot.slingshot :refer [throw+ try+]]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [grape.test-utils :refer :all]
            [clj-http.client :as http]
            [clj-time.format :as f])
  (:import (org.bson.types ObjectId)
           (org.joda.time DateTime)))

(deftest route-handlers
  (testing "get resource handler"
    (load-fixtures)
    (let [resource {:url        "myresource"
                    :operations #{:read}}
          routes ["/" (build-resources-routes {:resources-registry {:myresource resource}})]
          resource-match (match-route routes "/myresource")
          item-match (match-route routes "/myresource/1234")]
      (is (nil? (match-route routes "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "1234" (get-in item-match [:route-params :_id])))))

  (testing "get resource handler with vector url"
    (load-fixtures)
    (let [resource {:url        ["prefix/" :prefix "/myresource"]
                    :operations #{:read}}
          routes ["/" (build-resources-routes {:resources-registry {:myresource resource}})]
          resource-match (match-route routes "/prefix/toto/myresource")
          item-match (match-route routes "/prefix/toto/myresource/1234")]
      (is (nil? (match-route routes "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "toto" (get-in resource-match [:route-params :prefix])))
      (is (= "toto" (get-in item-match [:route-params :prefix])))
      (is (= "1234" (get-in item-match [:route-params :_id])))))

  (testing "get resource handler with extra endpoints"
    (load-fixtures)
    (let [resource {:url          "myresource"
                    :operations   #{:read}
                    :item-aliases [["extra" identity]
                                   ["other" identity]]}
          routes ["/" (build-resource-routes {} resource)]
          resource-match (match-route routes "/myresource")
          item-match (match-route routes "/myresource/1234")
          extra-match (match-route routes "/extra")
          other-match (match-route routes "/other")]
      (is (nil? (match-route routes "/unknown")))
      (is (not (nil? (:handler resource-match))))
      (is (= "1234" (get-in item-match [:route-params :_id])))
      (is (not (nil? (:handler extra-match))))
      (is (not (nil? (:handler other-match)))))))

(deftest get-resource
  (testing "get public users"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/public_users")
          handler (:handler match)
          request {:query-params   {"query" ""}
                   :request-method :get}
          resp (:body (handler request))]
      (is (= 3 (:_count resp)))
      (is (= #{:_id :godchild :username} (->> (:_items resp)
                                              first
                                              keys
                                              (into #{}))))
      (is (= #{"user 1" "user 2" "user 3"} (->> (:_items resp)
                                                (map :username)
                                                (into #{})))))))

(deftest create-resource
  (testing "create user - validation fails - required fields"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/users"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :body             "{}"})]
          (is (= (:status resp) 422))
          (is (= (get-in resp [:body :_issues])
                 {:email "missing-required-key" :password "missing-required-key" :username "missing-required-key"}))))))

  (testing "create user - validation fails username and email invalids"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/users"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :body             (generate-string {:email "coucou" :password "secret" :username "$°+#@"})})]
          (is (= (:status resp) 422))
          (is (= (get-in resp [:body :_issues])
                 {:email "email-should-be-valid" :username "username-should-be-valid"}))))))

  (testing "create user - validation fails username already exists"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/users"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :body             (generate-string {:email "coucou@coucou.com" :password "secret" :username "user 1"})})]
          (is (= (:status resp) 422))
          (is (= (get-in resp [:body :_issues])
                 {:username "username-should-be-unique"}))))))

  (testing "create user - validation fails friend not found"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/users"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :body             (generate-string {:email   "coucou@coucou.com" :password "secret" :username "abc"
                                                                   :friends [(ObjectId. "abcabcabcabcabcabcabcabc")]})})]
          (is (= (:status resp) 422))
          (is (= (get-in resp [:body :_issues])
                 {:friends ["resource-should-exist"]}))))))

  (testing "create user - validation fails - invalid type"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/users"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :body             (generate-string {:email "new@email.com" :password "secret" :username "new"
                                                                   :type  "unknown"})})]
          (is (= (:status resp) 422))
          (is (= (get-in resp [:body :_issues])
                 {:type "type-should-by-valid"}))))))

  (testing "create user - validation success"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/users"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :body             (generate-string {:email "new@email.com" :password "secret" :username "new"
                                                                   :type  "user"})})]
          (is (= (:status resp) 201))))))

  (testing "create a comment should inject auth field when not specified"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/comments"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                               :body             (generate-string {:text "my comment"})})]
          (is (= (:status resp) 201))
          (is (= (get-in resp [:body :text]) "my comment"))
          (is (= (get-in resp [:body :user]) "aaaaaaaaaaaaaaaaaaaaaaa1"))))))

  (testing "create a comment specifying itself as a user should pass"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/comments"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                               :body             (generate-string {:text "my comment" :user "aaaaaaaaaaaaaaaaaaaaaaa1"})})]
          (is (= (:status resp) 201))
          (is (= (get-in resp [:body :text]) "my comment"))
          (is (= (get-in resp [:body :user]) "aaaaaaaaaaaaaaaaaaaaaaa1"))))))

  (testing "create a comment specifying another user should be forbidden"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/comments"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                               :body             (generate-string {:text "my comment" :user "aaaaaaaaaaaaaaaaaaaaaaa2"})})]
          (is (= (:status resp) 403))))))

  (testing "create a comment should insert _created automatically"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [resp (http/post "http://localhost:8080/comments"
                              {:content-type     :json
                               :coerce           :always
                               :throw-exceptions false
                               :as               :json
                               :query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                               :body             (generate-string {:text "my comment"})})]
          (is (= (:status resp) 201))
          (is (instance? DateTime (f/parse (f/formatters :date-time) (get-in resp [:body :_created]))))
          (let [id (get-in resp [:body :_id])
                resp (http/get (str "http://localhost:8080/comments/" id)
                               {:as :json})]
            (is (instance? DateTime (f/parse (f/formatters :date-time) (get-in resp [:body :_created])))))))))
  )

(deftest update-resource
  (testing "update - validation fails - not found"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{status-put :status} (http/put "http://localhost:8080/users/aaaaaaaaaaaaaaaaaaaaaa99"
                                             {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaa99"})}
                                              :content-type     :json
                                              :coerce           :always
                                              :throw-exceptions false
                                              :as               :json})
              {status-patch :status} (http/patch "http://localhost:8080/users/aaaaaaaaaaaaaaaaaaaaaa99"
                                                 {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaa99"})}
                                                  :content-type     :json
                                                  :coerce           :always
                                                  :throw-exceptions false
                                                  :as               :json})]
          (is (= 404 status-put))
          (is (= 404 status-patch))))))

  (testing "update - validation fails - required fields"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{status :status {error :_error {:keys [username email password]} :_issues} :body}
              (http/put "http://localhost:8080/users/aaaaaaaaaaaaaaaaaaaaaaa1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 422 status))
          (is (= error "validation failed"))
          (is (= username "missing-required-key"))
          (is (= email "missing-required-key"))
          (is (= password "missing-required-key"))))))

  (testing "update comment - forbidden - user provided should be self"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{status :status :as resp}
              (http/put "http://localhost:8080/comments/ccccccccccccccccccccccc1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:user "aaaaaaaaaaaaaaaaaaaaaaa2"
                                                             :text "toto"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 403 status))))))

  (testing "update success"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{status :status {text :text} :body}
              (http/put "http://localhost:8080/comments/ccccccccccccccccccccccc1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:text "toto"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 200 status))
          (is (= "toto" text))))))

  (testing "update succes should change the _updated date"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{{previous-updated :_updated} :body} (http/get "http://localhost:8080/comments/ccccccccccccccccccccccc1" {:as :json})
              {status :status {updated :_updated} :body}
              (http/put "http://localhost:8080//comments/ccccccccccccccccccccccc1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:text "toto"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 200 status))
          (is (instance? DateTime (f/parse (f/formatters :date-time) updated)))
          (is (not= previous-updated updated))))))
  )

(deftest partial-update-resource
  (testing "partial update - validation fails - not found"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/users/aaaaaaaaaaaaaaaaaaaaaa99")
          handler (:handler match)
          request {:auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaa99")}
                   :request-method :patch
                   :body           {}
                   :route-params   (:route-params match)}
          {status :status} (handler request)]
      (is (= 404 status))))

  ;(testing "partial update comment - validation fails - user not found"
  ;  (load-fixtures)
  ;  (let [routes ["/" (build-resources-routes deps)]
  ;        match (match-route routes "/comments/ccccccccccccccccccccccc1")
  ;        handler (:handler match)
  ;        request {:auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}
  ;                 :body           {:user (ObjectId. "ffffffffffffffffffffffff")}
  ;                 :request-method :patch
  ;                 :route-params   (:route-params match)}
  ;        resp (handler request)]
  ;    (is (= 422 (:status resp)))
  ;    (is (= {:_error "validation failed" :_issues {:user "the resource should exist"}} (:body resp)))))

  (testing "partial update comment - validation fails - user should be self"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/comments/ccccccccccccccccccccccc1")
          handler (:handler match)
          request {:auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}
                   :body           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2")}
                   :request-method :patch
                   :route-params   (:route-params match)}
          resp (handler request)]
      (is (= 403 (:status resp)))))

  (testing "partial update success"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/users/aaaaaaaaaaaaaaaaaaaaaaa1")
          handler (:handler match)
          request {:body           {:username "newone"}
                   :auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}
                   :request-method :patch
                   :route-params   (:route-params match)}
          {status :status {:keys [username email]} :body} (handler request)]
      (is (= status 200))
      (is (= username "newone"))))

  (testing "partial update a user should change the _updated date"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/users/aaaaaaaaaaaaaaaaaaaaaaa1")
          now-before-update (t/now)
          handler (:handler match)
          request {:body           {:username "newone"}
                   :auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}
                   :request-method :patch
                   :route-params   (:route-params match)}
          resp (handler request)]
      (is (instance? DateTime (get-in resp [:body :_updated])))
      (is (< (c/to-long now-before-update) (c/to-long (get-in resp [:body :_updated]))))))
  )

(deftest delete-resource
  (testing "delete - not found"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/users/aaaaaaaaaaaaaaaaaaaaaa99")
          handler (:handler match)
          request {:auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaa99")}
                   :request-method :delete
                   :body           {}
                   :route-params   (:route-params match)}
          {status :status} (handler request)]
      (is (= 404 status))))

  (testing "delete - unauthorized"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/users/aaaaaaaaaaaaaaaaaaaaaaa1")
          handler (:handler match)
          request {:auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa2")}
                   :request-method :delete
                   :body           {}
                   :route-params   (:route-params match)}
          {status :status :as resp} (handler request)]
      (is (= 403 status))))

  (testing "delete - success"
    (load-fixtures)
    (let [routes ["/" (build-resources-routes deps)]
          match (match-route routes "/users/aaaaaaaaaaaaaaaaaaaaaaa1")
          handler (:handler match)
          request {:auth           {:user (ObjectId. "aaaaaaaaaaaaaaaaaaaaaaa1")}
                   :request-method :delete
                   :body           {}
                   :route-params   (:route-params match)}
          {status :status} (handler request)]
      (is (= 204 status))))
  )
