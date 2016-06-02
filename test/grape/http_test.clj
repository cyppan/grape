(ns grape.http-test
  (:require [clojure.test :refer :all]
            [grape.http :refer :all]))

;; token generated with jwt.io
;; {"aud": "api", "user-id": "57503897eeb06b64ada8fa08"}
(def token "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhcGkiLCJzdWIiOiIxMjM0NTY3ODkwIiwidXNlci1pZCI6IjU3NTAzODk3ZWViMDZiNjRhZGE4ZmEwOCJ9.KCVn0lDXiHnYJJp7DxEn5fEwPhF4O-HEGHDCqvl6Z4Y")

(deftest auth-middleware
  (testing "request is correctly enriched"
    (let [request {:query-params {"access_token" token}}
          ;; we setup a simple handler that responds with the incoming request
          handler (wrap-jwt-auth identity {:audience "api" :secret "secret"})
          response (handler request)]
      (is (= "57503897eeb06b64ada8fa08" (get-in response [:auth :user-id]))))
    ))
