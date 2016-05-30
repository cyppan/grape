(ns grape.hooks.inject-pagination)

(def ^:const max-limit 100)

(def hooks
  {:pre-fetch (fn [{:keys [config]} resource request query]
                (if (get-in query [:opts :paginate?])
                  (let [page (get-in query [:paginate :page] 1)
                        request-per-page (get-in query [:paginate :per-page])
                        resource-per-page (get-in resource [:default-paginate :per-page])
                        config-per-page (get-in config [:default-paginate :per-page] max-limit)
                        max-per-page (or resource-per-page config-per-page)
                        per-page (cond
                                   (and request-per-page (> request-per-page max-per-page)) max-per-page
                                   :else (or request-per-page max-per-page))]
                    (assoc-in query [:paginate] {:page page :per-page per-page}))
                  query))})