(ns grape.hooks.restricts-fields
  (:require [grape.utils :refer :all]))

(def hooks
  {:pre-read (fn [deps resource request query]
               (let [query-fields (map keyword (vec (:fields query [])))
                     resource-fields (when-let [fields (:fields resource)] (into #{} fields))
                     schema-fields (into #{}
                                         (map #(keyword (clojure.string/join "." (map name %)))
                                              (expand-keyseqs (get-schema-keyseqs (:schema resource)) true)))
                     fields (if (seq query-fields)
                              (into [] (filter (or resource-fields schema-fields) query-fields))
                              (into [] (or resource-fields schema-fields)))]
                 (when (empty? fields)
                   (throw (ex-info "there is no field to fetch for your resource" {:type :restrict-fields})))
                 (assoc-in query [:fields] fields)))})
