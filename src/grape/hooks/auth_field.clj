(ns grape.hooks.auth-field)

(defn wrap-auth [op on-request-auth]
  (fn [deps resource request arg & _]
    (if (and (:auth-strategy resource)
             (not (op (:public-operations resource #{}))))
      (let [auth-strategy (:auth-strategy resource)
            auth-field? (= (:type auth-strategy) :field)
            doc-field (:doc-field auth-strategy)
            request-auth (:auth request)
            auth-value ((:auth-field auth-strategy) request-auth)
            has-role (:has-role auth-strategy)]
        (if auth-field?
          (if (not request-auth)
            (throw (ex-info "Unauthorized" {:type :unauthorized}))
            (if has-role
              (when (not (some has-role (:roles request-auth)))
                (throw (ex-info "Unauthorized" {:type :unauthorized})))
              (on-request-auth auth-value doc-field arg)))
          arg))
      arg)))

(def hooks
  {:pre-create-pre-validate
   (wrap-auth :create (fn [auth-value doc-field payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if-not existing-value
                                       auth-value
                                       existing-value)))))
   :pre-create-post-validate
   (wrap-auth :create (fn [auth-value doc-field payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if (and existing-value (not= (str existing-value) (str auth-value)))
                                       (throw (ex-info "Forbidden" {:type :forbidden}))
                                       auth-value)))))
   :pre-update-pre-validate
   (wrap-auth :update (fn [auth-value doc-field payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if-not existing-value
                                       auth-value
                                       existing-value)))))
   :pre-update-post-validate
   (wrap-auth :update (fn [auth-value doc-field payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if (and existing-value (not= (str existing-value) (str auth-value)))
                                       (throw (ex-info "Forbidden" {:type :forbidden}))
                                       auth-value)))))
   :pre-partial-update-pre-validate
   (wrap-auth :update (fn [auth-value doc-field payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if-not existing-value
                                       auth-value
                                       existing-value)))))
   :pre-partial-update-post-validate
   (wrap-auth :update (fn [auth-value doc-field payload]
                        (update-in payload [doc-field]
                                   (fn [existing-value]
                                     (if (and existing-value (not= (str existing-value) (str auth-value)))
                                       (throw (ex-info "Forbidden" {:type :forbidden}))
                                       auth-value)))))
   :pre-read
   (wrap-auth :read (fn [auth-value doc-field query]
                      (update-in query [:find]
                                 (fn [find]
                                   (let [existing-value (doc-field find)
                                         existing-value (if (and (get existing-value "$in") (= 1 (count (get existing-value "$in"))))
                                                          (first (get existing-value "$in"))
                                                          existing-value)]
                                     (if (and existing-value (not= (str existing-value) (str auth-value)))
                                       (throw (ex-info "Forbidden" {:type :forbidden}))
                                       (merge find {doc-field auth-value})))))))})
