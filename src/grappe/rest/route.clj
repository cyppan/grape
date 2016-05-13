(ns grappe.rest.route
  (:require [cheshire.core :refer :all]
            [grappe.store :as store]))

(def default-id-pattern #"[0-9a-f]{24}")

(defn rest-resource-handler [{:keys [resources-registry hooks] :as deps} resource {:keys [route-params] :as request}]
  (let [method (:request-method request)
        item-method? (boolean (get-in request [:route-params :_id]))
        ?404 (or
               (and (not item-method?) (not ((:resource-methods resource) method)))
               (and item-method? (not ((:item-methods resource) method))))
        get? (= :get method)
        post? (= :post method)
        patch? (= :patch method)
        put? (= :put method)
        delete? (= :delete method)
        private? (and
                   (:auth-strategy resource)
                   (condp = method
                     :post (not (:post (into #{} (:public-methods request))))
                     :get  (not (:get (into #{} (:public-methods request))))
                     :put  (not (:put (into #{} (:public-methods request))))
                     :patch (not (:patch (into #{} (:public-methods request))))
                     :delete (not (:delete (into #{} (:public-methods request))))))]
    (prn "query" (update-in (parse-string (get-in request [:query-params "query"]) true) [:find] (fn [find] (merge find (:route-params request)))))
    (cond
      ?404
      {:status 404 :body {:_status "404" :_message "The method is not supported for this resource"}}
      (and get? (not item-method?))
      (-> request
          (#(assoc % :query (update-in (parse-string (get-in request [:query-params "query"]) true) [:find] (fn [find] (merge find (:route-params request))))))
          (#(store/fetch-resource
             (assoc deps :resources-registry resources-registry
                         :hooks hooks) resource % {}))
          (#(assoc {:status 200} :body %)))
      (and get? item-method?)
      (-> request
          (#(assoc % :query (update-in (parse-string (get-in request [:query-params "query"]) true) [:find] (fn [find] (merge find (:route-params request))))))
          (#(store/fetch-item
             (assoc deps :resources-registry resources-registry
                         :hooks hooks) resource %))
          (#(assoc {:status 200} :body %))))))

(defn build-resource-routes [deps resource]
  (let [id-pattern (:id-pattern resource default-id-pattern)
        extra-routes (:extra-routes resource {})]
    (concat [{(:url resource) {"" (partial rest-resource-handler deps resource)
                               ["/" [id-pattern :_id]] (partial rest-resource-handler deps resource)}}]
            (for [[route-path handler] extra-routes]
              [route-path (partial handler deps resource)]))))
