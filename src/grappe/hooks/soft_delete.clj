(ns grappe.hooks.soft-delete)

(def hooks
  {:pre-fetch (fn [deps resource request query]
                (let [soft-delete? (:soft-delete resource)]
                  (prn "soft delete reached" soft-delete? query)
                  (if soft-delete?
                    (update-in query [:find]
                               #(if (:_deleted %) % (merge % {:_deleted {"$ne" true}})))
                    query)))})