(ns grape.hooks.auth-field)

(def hooks
  {:pre-fetch (fn [deps resource request query]
                (if (and (:auth-strategy resource)
                         (not ((:public-methods resource #{}) :get)))
                  (let [auth-strategy (:auth-strategy resource)
                        auth-field? (= (:type auth-strategy) :field)
                        doc-field (:doc-field auth-strategy)
                        request-auth (:auth request)
                        auth-value ((:auth-field auth-strategy) request-auth)]
                    (if auth-field?
                      (if (not request-auth)
                        (throw (ex-info "Unauthorized" {:status 401}))
                        (update-in query [:find]
                                   (fn [find]
                                     (let [existing-value (doc-field find)]
                                       (if (and existing-value (not= (str existing-value) (str auth-value)))
                                         (throw (ex-info "Forbidden" {:status 403}))
                                         (merge find {doc-field auth-value}))))))
                      query))
                  query))})
