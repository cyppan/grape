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
        (let [{status-put :status}
              (http/put "http://localhost:8080/comments/ccccccccccccccccccccccc1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:user "aaaaaaaaaaaaaaaaaaaaaaa2"
                                                             :text "toto"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})
              {status-patch :status}
              (http/patch "http://localhost:8080/comments/ccccccccccccccccccccccc1"
                          {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                           :body             (generate-string {:user "aaaaaaaaaaaaaaaaaaaaaaa2"})
                           :content-type     :json
                           :coerce           :always
                           :throw-exceptions false
                           :as               :json})]
          (is (= 403 status-put))
          (is (= 403 status-patch))))))

  (testing "update success"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{{previous-updated :_updated} :body} (http/get "http://localhost:8080/comments/ccccccccccccccccccccccc1" {:as :json})
              {status-put :status {updated-put :_updated text-put :text} :body}
              (http/put "http://localhost:8080/comments/ccccccccccccccccccccccc1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:text "toto"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})
              {status-patch :status {updated-patch :_updated text-patch :text} :body}
              (http/patch "http://localhost:8080/comments/ccccccccccccccccccccccc1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:text "modified"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 200 status-put))
          (is (= 200 status-patch))
          (is (= "toto" text-put))
          (is (= "modified" text-patch))
          (is (instance? DateTime (f/parse (f/formatters :date-time) updated-put)))
          (is (not= previous-updated updated-put))
          (is (instance? DateTime (f/parse (f/formatters :date-time) updated-patch)))
          (is (not= previous-updated updated-put updated-patch))))))

  (testing "update success should change the _updated date"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{{previous-updated :_updated} :body} (http/get "http://localhost:8080/comments/ccccccccccccccccccccccc1" {:as :json})
              {status :status {updated :_updated} :body}
              (http/put "http://localhost:8080/comments/ccccccccccccccccccccccc1"
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

  (testing "partial update failure - username already taken"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{{previous-updated :_updated} :body} (http/get "http://localhost:8080/comments/ccccccccccccccccccccccc1" {:as :json})
              {status :status {{:keys [username]} :_issues} :body}
              (http/patch "http://localhost:8080/users/aaaaaaaaaaaaaaaaaaaaaaa1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:username "user 2"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 422 status))
          (is (= username "username-should-be-unique"))))))

  (testing "partial update success"
    (with-test-system
      deps
      (fn [system]
        (load-fixtures system)
        (let [{{previous-updated :_updated} :body} (http/get "http://localhost:8080/comments/ccccccccccccccccccccccc1" {:as :json})
              {status :status {updated :_updated username :username} :body :as resp}
              (http/patch "http://localhost:8080/users/aaaaaaaaaaaaaaaaaaaaaaa1"
                        {:query-params     {"access_token" (encode-jwt system {:user "aaaaaaaaaaaaaaaaaaaaaaa1"})}
                         :body             (generate-string {:username "user 1"})
                         :content-type     :json
                         :coerce           :always
                         :throw-exceptions false
                         :as               :json})]
          (is (= 200 status))
          (is (= username "user 1"))
          (is (instance? DateTime (f/parse (f/formatters :date-time) updated)))
          (is (not= previous-updated updated))))))
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
