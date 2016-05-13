(ns grappe.hooks.restricts-fields)

(def hooks
  {:pre-fetch (fn [deps resource request query]
                (let [query-fields (:fields query)
                      resource-fields (set (:fields resource (keys (:schema resource))))]
                  (prn "restrict-fields reached" (if query-fields
                                                   (filter #(resource-fields (first (clojure.string/split % #"\."))) query-fields)
                                                   resource-fields))
                  (assoc-in query [:fields]
                            (if query-fields
                              (filter #(resource-fields (first (clojure.string/split % #"\."))) query-fields)
                              resource-fields))))})

