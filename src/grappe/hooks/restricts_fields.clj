(ns grappe.hooks.restricts-fields
  (:require [grappe.utils :refer :all]
            [grappe.schema :refer :all]))

(def hooks
  {:pre-fetch (fn [deps resource request query]
                (let [query-fields (map keyword (vec (:fields query [])))
                      resource-fields (:fields resource)
                      schema-fields (set
                                      (map #(keyword (clojure.string/join "." (map name %)))
                                           (expand-keyseqs (get-schema-keyseqs (:schema resource)) true)))]
                  (assoc-in query [:fields]
                            (if (seq query-fields)
                              (filter (or resource-fields schema-fields) query-fields)
                              (vec (or resource-fields schema-fields))))))})

