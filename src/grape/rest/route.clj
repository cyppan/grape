(ns grape.rest.route
  (:require [cheshire.core :refer :all]
            [grape.core :refer :all]
            [grape.rest.parser :refer [parse-query format-eve-response]]
            [grape.query :refer [validate-query]]
            [grape.schema :refer [validate-create validate-update validate-partial-update]]
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
           (#(update-in % [:opts] (fn [opts] (merge opts {:count? true}))))
           (fetch-resource deps resource request)
           (assoc {:status 200} :body)
           format-eve-response)
      (and item-method? (= method :get))
      (->> request
           parse-query
           (validate-query deps resource request)
           (fetch-item deps resource request)
           (assoc {:status 200} :body)
           format-eve-response)
      (and resource-method? (= method :post))
      (let [query (->> request
                       parse-query
                       (validate-query deps resource request))
            payload (->> (validate-create deps resource request (:body request)))]
        (->> request
             parse-query
             (validate-query deps resource request)))
      )))

(defn build-resource-routes [deps resource]
  (let [extra-endpoints (:extra-endpoints resource {})]
    (into []
          (concat [[(:url resource) (partial rest-resource-handler deps resource)]
                   [[(str (:url resource) "/") :_id] (partial rest-resource-handler deps resource)]]
                  (for [[route-path handler] extra-endpoints]
                    [route-path (partial handler deps resource)])))))

(defn build-resources-routes [{:keys [resources-registry] :as deps}]
  (let [resources (for [[_ resource] resources-registry] resource)]
    (mapcat identity (map (partial build-resource-routes deps) resources))))
