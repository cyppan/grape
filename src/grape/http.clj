(ns grape.http
  "Convenient httpserver component that automatically injects needed dependencies
  and auth middleware"
  (:require [com.stuartsierra.component :as component]
            [schema.core :as s]
            [clojure.tools.logging :as log])
  (:use org.httpkit.server)
  (:import (com.auth0.jwt JWTVerifier)))

;; Types definitions

(def HTTPServerConfig {:host s/Str :port s/Int s/Any s/Any})
(def JWTConfig) {:audience s/Str :secret s/Any}
(def KeywordMap {s/Keyword s/Any})
(def Function (s/=> {s/Any s/Any} {s/Any s/Any}))
(def RingHandlerBuilder Function)
(def RingHandler Function)
(def WrapperBuilder Function)
(def ComponentDependencies (s/if map? {s/Keyword s/Keyword} [s/Keyword]))

;;;;;;;;;;;;;;;;;;;;;;;
;; # Middleware
;;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-jwt-auth [handler config]
  (let [{:keys [audience secret]} config
        verifier (JWTVerifier. secret audience)]
    (fn [request]
      (if-let [token (get-in request [:query-params "access_token"])]
        (try
          (let [claims (.verify verifier token)]
            (->> (into {} claims)
                 clojure.walk/keywordize-keys
                 (assoc request :auth)
                 handler))
          (catch Exception _ nil))
        (handler request)))))

;;;;;;;;;;;;;;;;;;;;;;;
;; # HTTPServer component
;;;;;;;;;;;;;;;;;;;;;;;



;; Middleware which merges the dependencies map into the request object.
(s/defn wrap-dependencies :- RingHandler
  ""
  [handler-builder :- RingHandlerBuilder
   dependencies :- KeywordMap]
  (let [handler (handler-builder dependencies)]
    handler))

;; The HTTPServer component is meant to be used with the components lib,
;; it automatically injects the component-managed declared dependencies
;;
;; Use the new-http-server factory below to instantiate,
;; you have an example app in the comment at the bottom of the file"
(s/defrecord HTTPServer [;; construction parameters
                         handler :- RingHandlerBuilder
                         wrapper :- WrapperBuilder
                         conf :- HTTPServerConfig
                         ;; runtime params
                         server :- (s/maybe s/Any)]
  component/Lifecycle
  (start [this]
    (log/info "starting http-kit server listening on " (:host conf) ":" (:port conf))
    (if (:server this)
      this
      (assoc this :server
                  (run-server ((wrapper (dissoc this :handler :conf :server :wrapper))
                                (wrap-dependencies handler (dissoc this :handler :conf :server :wrapper)))
                              conf))))
  (stop [this]
    (log/info "stopping HTTP server")
    (if (:server this)
      (do ((:server this) :timeout 100)
          (assoc this :server nil))
      this)))


;; builds a new http server component:
;;
;; - config: a map containing the ring config :host and :port at least
;; - handler: a ring handler builder (function with dependencies map as param and returning a ring handler)
;; - dependencies: the component dependencies
;;
;; **Ex:**
;;
;;      (component/system-map
;;        :mongo-db (new-mongo-db (:mongo-db config))
;;        :http-server (new-http-server (:http-server config)
;;                                      routes-builder
;;                                      wrapper-builder
;;                                      [:mongo-db]))
;;
(s/defn ^:always-validate new-http-server :- HTTPServer
  [conf :- HTTPServerConfig
   handler :- RingHandlerBuilder
   wrapper :- WrapperBuilder
   dependencies :- ComponentDependencies]
  (component/using (map->HTTPServer {:handler handler :wrapper wrapper :conf conf})
                   dependencies))
