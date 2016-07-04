(ns grape.hooks.inject-pagination)

(def ^:const max-limit 100)

(def hooks
  {:pre-read (fn [{:keys [config]} resource request query]
               (if (get-in query [:opts :paginate?])
                 (let [page (or (get-in query [:paginate :page]) 1)
                       skip (or (get-in query [:paginate :skip]) 0)
                       request-per-page (get-in query [:paginate :per-page])
                       request-limit (get-in query [:paginate :limit])
                       resource-per-page (get-in resource [:default-paginate :per-page])
                       config-per-page (get-in config [:default-paginate :per-page] max-limit)
                       max-per-page (or resource-per-page config-per-page)
                       max-limit max-per-page
                       per-page (cond
                                  (and request-per-page (> request-per-page max-per-page)) max-per-page
                                  :else (or request-per-page max-per-page))
                       limit (cond
                               (and request-limit (> request-limit max-limit)) max-limit
                               :else request-limit)]
                   (if limit
                    (assoc-in query [:paginate] {:limit limit :skip skip})
                    (assoc-in query [:paginate] {:page page :per-page per-page})))
                 query))})