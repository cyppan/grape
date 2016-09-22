(ns grape.hooks.restricts-fields
  (:require [grape.schema :refer :all]
            [grape.utils :refer [expand-keyseqs]])
  (:import (grape.schema Hidden)))

(def hooks
  {:pre-read (fn [deps resource request query]
               (let [query-fields (map keyword (vec (:fields query [])))
                     resource-fields (when-let [fields (:fields resource)] (into #{} fields))
                     schema-fields (->> (get-schema-keyseqs (:schema resource) :skip-hidden? true)
                                        (map #(filter (partial not= []) %))
                                        (map #(keyword (clojure.string/join "." (map name %))))
                                        (into #{}))
                     default-fields (->> (get-schema-keyseqs (:schema resource) :skip-hidden? true)
                                         (filter #(= (count %) 1))
                                         (map first)
                                         (into #{}))
                     fields (if (seq query-fields)
                              (into [] (filter (or resource-fields schema-fields) query-fields))
                              (into [] (or resource-fields default-fields)))]
                 (when (empty? fields)
                   (throw (ex-info "there is no field to fetch for your resource" {:type :restrict-fields})))
                 (assoc-in query [:fields] fields)))})
