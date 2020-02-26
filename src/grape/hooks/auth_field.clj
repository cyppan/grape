(ns grape.hooks.auth-field)

(defn wrap-auth [op on-request-auth]
  (fn [deps resource request arg & _]
    (if (and (:auth-strategy resource)
             (not (op (:public-operations resource #{}))))
      (let [auth-strategy (:auth-strategy resource)
            auth-field? (= (:type auth-strategy) :field)
            doc-field (:doc-field auth-strategy)
            additional-permission-doc-fields (or (:additional-permission-doc-fields auth-strategy) [])
            request-auth (:auth request)
            auth-value ((:auth-field auth-strategy) request-auth)]
        (if auth-field?
          (if (not request-auth)
            (throw (ex-info "Unauthorized" {:type :unauthorized}))
            (on-request-auth auth-value doc-field additional-permission-doc-fields arg))
          arg))
      arg)))

(def has-rights-over [payload doc-field additional-permission-doc-fields auth-value]
  (let [fields (concat [(get payload doc-field)] (map additional-permission-doc-fields #((get payload %))))]
    (contains? fields auth-value))

(def hooks
  {:pre-create-pre-validate
   (wrap-auth :create (fn [auth-value doc-field additional-permission-doc-fields payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if-not existing-value
                                       auth-value
                                       existing-value)))))
  :pre-update-post-validate
   (wrap-auth :update (fn [auth-value doc-field additional-permission-doc-fields payload]
                        (if (has-rights-over payload doc-field additional-permission-doc-fields auth-value)
                              payload
                              (throw (ex-info "Forbidden" {:type :forbidden})))))
   :pre-partial-update-post-validate
   (wrap-auth :update (fn [auth-value doc-field additional-permission-doc-fields payload]
                        (if (has-rights-over payload doc-field additional-permission-doc-fields auth-value)
                              payload
                              (throw (ex-info "Forbidden" {:type :forbidden})))))
   :pre-read
   (wrap-auth :read (fn [auth-value doc-field additional-permission-doc-fields query]
                      (update-in query [:find]
                                 (fn [find]
                                   (let [existing-value (doc-field find)
                                         existing-value (if (and (get existing-value "$in") (= 1 (count (get existing-value "$in"))))
                                                          (first (get existing-value "$in"))
                                                          existing-value)]
                                     (if (and existing-value (not= (str existing-value) (str auth-value)))
                                       (throw (ex-info "Forbidden" {:type :forbidden}))
                                       (merge find {doc-field auth-value})))))))})
