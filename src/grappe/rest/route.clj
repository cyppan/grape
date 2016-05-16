(ns grappe.rest.route
  (:require [cheshire.core :refer :all]
            [grappe.store :as store]
            [grappe.rest.parser :refer [parse-query format-eve-response]]
            [grappe.query :refer [validate-query]]
            [plumbing.core :refer :all]))

(defn rest-resource-handler [deps resource request]
  (let [method (:request-method request)
        item-method? (boolean (get-in request [:route-params :_id]))
        resource-method? (not item-method?)
        private? (and
                   (:auth-strategy resource)
                   (condp = method
                     :post (not (:post (into #{} (:public-methods request))))
                     :get (not (:get (into #{} (:public-methods request))))
                     :put (not (:put (into #{} (:public-methods request))))
                     :patch (not (:patch (into #{} (:public-methods request))))
                     :delete (not (:delete (into #{} (:public-methods request))))))
        allowed-method? (if item-method?
                          (method (:item-methods resource #{}))
                          (method (:resource-methods resource #{})))]
    (cond
      (not allowed-method?)
      {:status 404 :body {:_status "404" :_message "The method is not supported for this resource"}}
      (and private? (not (:auth request)))
      (throw (ex-info "Unauthorized" {:status 401}))
      (and resource-method? (= method :get))
      (->> request
           parse-query
           (validate-query deps resource request)
           (store/fetch-resource deps resource request)
           (assoc {:status 200} :body)
           format-eve-response)
      (and item-method? (= method :get))
      (->> request
           parse-query
           (validate-query deps resource request)
           (store/fetch-item deps resource request)
           (assoc {:status 200} :body)
           format-eve-response)
      :else
      {:status 501 :body {:_status "501" :_message "Not implemented yet"}})))

(defn build-resource-routes [deps resource]
  (let [extra-endpoints (:extra-endpoints resource {})]
    ["/" (into []
               (concat [[(:url resource) (partial rest-resource-handler deps resource)]
                        [[(str (:url resource) "/") :_id] (partial rest-resource-handler deps resource)]]
                       (for [[route-path handler] extra-endpoints]
                         [route-path (partial handler deps resource)])))]))
