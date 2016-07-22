(ns grape.rest.route
  (:require [cheshire.core :refer :all]
            [grape.core :refer :all]
            [grape.rest.parser :refer [parse-query format-eve-response]]
            [grape.query :refer [validate-query]]
            [grape.schema :refer [validate-create validate-update validate-partial-update]]
            [plumbing.core :refer :all]
            [bidi.ring :refer (make-handler)]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]])
  (:import (clojure.lang ExceptionInfo)
           (com.fasterxml.jackson.core JsonGenerationException)))

(defn rest-resource-handler [deps resource request]
  (let [method (:request-method request)
        item-method? (boolean (get-in request [:route-params :_id]))
        resource-method? (not item-method?)
        resource-name (->> (:resources-registry deps)
                           (filter #(= resource (second %)))
                           (map first)
                           first)
        private? (and
                   (:auth-strategy resource)
                   (condp = method
                     :post (not (:create (into #{} (:public-operations resource))))
                     :get (not (:read (into #{} (:public-operations resource))))
                     :put (not (:update (into #{} (:public-operations resource))))
                     :patch (not (:update (into #{} (:public-operations resource))))
                     :delete (not (:delete (into #{} (:public-operations resource))))))
        allowed-method? (or
                          (and item-method? (= :get method) (:read (:operations resource #{})))
                          (and item-method? (= :put method) (:update (:operations resource #{})))
                          (and item-method? (= :patch method) (:update (:operations resource #{})))
                          (and item-method? (= :delete method) (:delete (:operations resource #{})))
                          (and resource-method? (= :get method) (:read (:operations resource #{})))
                          (and resource-method? (= :post method) (:create (:operations resource #{}))))]
    (try+
      (cond
        (not allowed-method?)
        (throw (ex-info "The method is not supported for this resource" {:type :not-found}))
        (and private? (not (:auth request)))
        (throw (ex-info "Unauthorized" {:type :unauthorized}))
        (and resource-method? (= method :get))
        (->> request
             parse-query
             (#(update-in % [:opts] (fn [opts] (merge {:count? true} opts {:paginate? true}))))
             (read-resource deps resource request)
             (assoc {:status 200} :body)
             format-eve-response)
        (and item-method? (= method :get))
        (->> request
             parse-query
             (read-item deps resource request)
             (assoc {:status 200} :body)
             format-eve-response)
        (and resource-method? (= method :post))
        (let [payload (merge (:body request {}) (:route-params request))]
          (->> (create-resource deps resource request payload)
               (assoc {:status 201} :body)
               format-eve-response))
        (and item-method? (= method :put))
        (let [find (:route-params request)
              payload (merge (:body request {}) find)]
          (->> (update-resource deps resource request find payload)
               (assoc {:status 200} :body)
               format-eve-response))
        (and item-method? (= method :patch))
        (let [find (:route-params request)
              payload (merge (:body request {}) find)]
          (->> (partial-update-resource deps resource request find payload)
               (assoc {:status 200} :body)
               format-eve-response))
        (and item-method? (= method :delete))
        (let [find (:route-params request)]
          (->> (delete-resource deps resource request find)
               (assoc {:status 204} :body)
               format-eve-response))
        :else {:status 404 :body {:_error (str method " method is unsupported for the resource " resource-name)}})
      (catch [:type :unauthorized] _
        {:status 401 :body {:_error (str "you're not allowed to access the resource " resource-name)}})
      (catch [:type :not-found] _
        {:status 404 :body {:_error "the resource cannot be found"}})
      (catch [:type :forbidden] _
        {:status 403 :body {:_error (str "you're not allowed to access the resource " resource-name)}})
      (catch [:type :validation-failed] {:keys [error]}
        (if error
          (try
            (generate-string error)
            ;; let the middleware generate json and set the relevant content-type header
            {:status 422
             :body   {:_error  "validation failed"
                      :_issues error}}
            (catch JsonGenerationException ex
              (log/warn "failed to json encode validation failure" ex)
              {:status 422 :body {:_error (str "validation failed " error)}}))
          {:status 422 :body {:_error (:message &throw-context)}})))))

(defn build-resource-routes [deps resource]
  (let [item-aliases (:item-aliases resource {})
        resource-url (:url resource)
        item-url (if (vector? resource-url)
                   (conj resource-url "/" :_id)
                   [resource-url "/" :_id])]
    (into []
          (concat [[resource-url (partial rest-resource-handler deps resource)]
                   [item-url (partial rest-resource-handler deps resource)]]
                  (for [[route-path handler] item-aliases]
                    [route-path (fn [request]
                                  (->> (handler deps resource request)
                                       (#(rest-resource-handler deps resource (assoc-in request [:route-params :_id] %)))))])))))

(defn build-resources-routes [{:keys [resources-registry] :as deps}]
  (let [resources (for [[_ resource] resources-registry] resource)]
    (into []
          (mapcat identity (map (partial build-resource-routes deps) resources)))))

(defn handler-builder
  ([deps]
   (handler-builder deps "/"))
  ([deps prefix]
   (make-handler [prefix (build-resources-routes deps)])))
