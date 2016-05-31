(ns grape.rest.route
  (:require [cheshire.core :refer :all]
            [grape.core :refer :all]
            [grape.rest.parser :refer [parse-query format-eve-response]]
            [grape.query :refer [validate-query]]
            [grape.schema :refer [validate-create validate-update validate-partial-update]]
            [plumbing.core :refer :all])
  (:import (clojure.lang ExceptionInfo)))

(defn rest-resource-handler [deps resource request]
  (let [method (:request-method request)
        item-method? (boolean (get-in request [:route-params :_id]))
        resource-method? (not item-method?)
        private? (and
                   (:auth-strategy resource)
                   (condp = method
                     :post (not (:post (into #{} (:public-methods resource))))
                     :get (not (:get (into #{} (:public-methods resource))))
                     :put (not (:put (into #{} (:public-methods resource))))
                     :patch (not (:patch (into #{} (:public-methods resource))))
                     :delete (not (:delete (into #{} (:public-methods resource))))))
        allowed-method? (if item-method?
                          (method (:item-methods resource #{}))
                          (method (:resource-methods resource #{})))]
    (try
      (cond
        (not allowed-method?)
        (throw (ex-info "The method is not supported for this resource" {:type :not-found}))
        (and private? (not (:auth request)))
        (throw (ex-info "Unauthorized" {:type :unauthorized}))
        (and resource-method? (= method :get))
        (->> request
             parse-query
             (#(update-in % [:opts] (fn [opts] (merge opts {:count? true}))))
             (fetch-resource deps resource request)
             (assoc {:status 200} :body)
             format-eve-response)
        (and item-method? (= method :get))
        (->> request
             parse-query
             (fetch-item deps resource request)
             (assoc {:status 200} :body)
             format-eve-response)
        (and resource-method? (= method :post))
        (let [payload (merge (:body request {}) (:route-params request))]
          (->> (create-resource deps resource request payload)
               (assoc {:status 201} :body)
               format-eve-response))
        (and item-method? (= method :put))
        (let [payload (merge (:body request {}) (:route-params request))]
          (->> (update-resource deps resource request payload)
               (assoc {:status 201} :body)
               format-eve-response))
        (and item-method? (= method :patch))
        (let [payload (merge (:body request {}) (:route-params request))]
          (->> (create-resource deps resource request payload)
               (assoc {:status 201} :body)
               format-eve-response))
        (and item-method? (= method :delete))
        (let [payload (merge (:body request {}) (:route-params request))]
          (->> (create-resource deps resource request payload)
               (assoc {:status 201} :body)
               format-eve-response))
        )
      (catch ExceptionInfo ex
        (condp = (:type (ex-data ex))
          :unauthorized {:status 401}
          :not-found {:status 404}
          :validation-failed {:status 422 :body (:error (ex-data ex))}
          :forbidden {:status 403}
          (do (prn (.getMessage ex) (ex-data ex))
              {:status 500 :body {:_status "ERR" :_message "an unexpected error occured"}}))))))

(defn build-resource-routes [deps resource]
  (let [extra-endpoints (:extra-endpoints resource {})]
    (into []
          (concat [[(:url resource) (partial rest-resource-handler deps resource)]
                   [[(str (:url resource) "/") :_id] (partial rest-resource-handler deps resource)]]
                  (for [[route-path handler] extra-endpoints]
                    [route-path (fn [request]
                                  (->> (handler deps resource request)
                                       (assoc {:status 200} :body)
                                      (format-eve-response)))])))))

(defn build-resources-routes [{:keys [resources-registry] :as deps}]
  (let [resources (for [[_ resource] resources-registry] resource)]
    (mapcat identity (map (partial build-resource-routes deps) resources))))
