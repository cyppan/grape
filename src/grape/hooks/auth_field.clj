(ns grape.hooks.auth-field)

(def hooks
  {:pre-create-pre-validate
   (fn [deps resource request payload]
     (if (and (:auth-strategy resource)
              (not ((:public-methods resource #{}) :post)))
       (let [auth-strategy (:auth-strategy resource)
             auth-field? (= (:type auth-strategy) :field)
             doc-field (:doc-field auth-strategy)
             request-auth (:auth request)
             auth-value ((:auth-field auth-strategy) request-auth)]
         (if auth-field?
           (if (not request-auth)
             (throw (ex-info "Unauthorized" {:type :unauthorized}))
             (update-in payload [doc-field]
                        (fn [existing-value]
                            (if (and existing-value (not= (str existing-value) (str auth-value)))
                              (throw (ex-info "Forbidden" {:type :forbidden}))
                              auth-value))))))
       payload))
   :pre-fetch
   (fn [deps resource request query]
     (if (and (:auth-strategy resource)
              (not ((:public-methods resource #{}) :get)))
       (let [auth-strategy (:auth-strategy resource)
             auth-field? (= (:type auth-strategy) :field)
             doc-field (:doc-field auth-strategy)
             request-auth (:auth request)
             auth-value ((:auth-field auth-strategy) request-auth)]
         (if auth-field?
           (if (not request-auth)
             (throw (ex-info "Unauthorized" {:type :unauthorized}))
             (update-in query [:find]
                        (fn [find]
                          (let [existing-value (doc-field find)]
                            (if (and existing-value (not= (str existing-value) (str auth-value)))
                              (throw (ex-info "Forbidden" {:type :forbidden}))
                              (merge find {doc-field auth-value}))))))
           query))
       query))})
