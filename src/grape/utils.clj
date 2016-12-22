(ns grape.utils
  (:require [schema.core :as s]))

(defn ->PascalCase [input]
  (let [formatter (cond
                    (keyword? input) keyword
                    (symbol? input) symbol
                    :else identity)
        input (name input)]
    (->> (clojure.string/split input #"(-+|_+| +)")
         (map clojure.string/capitalize)
         (apply str)
         formatter)))

(defn deep-merge
  "Recursively merges maps. If keys are not maps, the last value wins."
  [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

(defn walk-structure [s key-fn leaf-fn]
  (cond
    (map? s)
    (reduce (fn [acc [k v]]
              (assoc acc (key-fn k) (walk-structure v key-fn leaf-fn)))
            {} s)
    (sequential? s)
    (reduce (fn [acc v]
              (conj acc (walk-structure v key-fn leaf-fn)))
            [] (or s []))
    :else
    (leaf-fn s)))
