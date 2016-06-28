(ns grape.hooks.restricts-fields
  (:require [grape.schema :refer :all]
            [grape.utils :refer [expand-keyseqs]])
  (:import (grape.schema WriteOnly)))

(def hooks
  {:pre-read (fn [deps resource request query]
               (let [query-fields (map keyword (vec (:fields query [])))
                     resource-fields (when-let [fields (:fields resource)] (into #{} fields))
                     schema-fields (into #{}
                                         (map #(keyword (clojure.string/join "." (map name %)))
                                              (->> (flatten-schema (:schema resource))
                                                   (filter #(not (:grape/write-only (meta (second %)))))
                                                   (map first)
                                                   (#(expand-keyseqs % true)))))
                     default-fields (into #{}
                                         (map #(keyword (clojure.string/join "." (map name %)))
                                              (->> (flatten-schema (:schema resource))
                                                   (filter #(not (:grape/write-only (meta (second %)))))
                                                   (map first)
                                                   (map (fn [ks] (filter (partial not= []) ks))))))
                     fields (if (seq query-fields)
                              (into [] (filter (or resource-fields schema-fields) query-fields))
                              (into [] (or resource-fields default-fields)))]
                 (when (empty? fields)
                   (throw (ex-info "there is no field to fetch for your resource" {:type :restrict-fields})))
                 (assoc-in query [:fields] fields)))})
