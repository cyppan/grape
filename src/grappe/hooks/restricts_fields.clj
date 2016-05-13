(ns grappe.hooks.restricts-fields)

(def hooks
  {:pre-fetch (fn [deps resource request query]
                (let [query-fields (vec (:fields query []))
                      resource-fields (set (:fields resource (keys (:schema resource))))]
                  (assoc-in query [:fields]
                            (if (seq query-fields)
                              (filter #(resource-fields (first (clojure.string/split (name %) #"\."))) query-fields)
                              (vec resource-fields)))))})

