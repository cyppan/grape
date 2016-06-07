(ns grape.utils
  (:require [schema.core :as s]
            [com.rpl.specter :refer :all]
            [com.rpl.specter.macros :refer :all])
  (:import (schema.spec.leaf LeafSpec)
           (schema.spec.variant VariantSpec)
           (schema.spec.collection CollectionSpec)
           (schema.core Maybe)))

(defn deep-merge
  "Recursively merges maps. If keys are not maps, the last value wins."
  [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

(defn flatten-structure
  "Transform a nested map into a seq of [keyseq leaf-val] pairs. Supports expansion of sequential too"
  [m]
  (when m
    ((fn flatten-helper [keyseq m]
       (when m
         (cond
           (map? m)
           (mapcat (fn [[k v]] (flatten-helper (conj keyseq k) v)) m)
           (sequential? m)
           (mapcat identity (map #(flatten-helper (conj keyseq []) %) m))
           :else
           [[keyseq m]])))
      [] m)))

(defn expand-keyseq [ks]
  (if (and ks (seq ks))
    (loop [prefix []
           [h & t] ks
           acc '()]
      (if (empty? t)
        (cons (conj prefix h) acc)
        (recur (conj prefix h) t (cons (conj prefix h) acc))))
    []))

(defn expand-keyseqs [ks filter-seq?]
  (let [expanded (volatile! [])]
    (->> ks
         (map #(if filter-seq? (filter (partial not= []) %) %))
         (map expand-keyseq)
         (clojure.walk/postwalk #(if (and
                                       (sequential? %)
                                       (every? (complement sequential?) %))
                                  (do
                                    (vswap! expanded (fn [expanded] (conj expanded %)))
                                    %)
                                  %)))
    (-> @expanded set)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn walk-schema [s key-fn leaf-fn]
  (let [s (if (instance? Maybe s)
            (:schema s)
            s)
        spec (s/spec s)]
    (cond
      (or (instance? LeafSpec spec) (instance? VariantSpec spec))
      (leaf-fn s)
      (and (instance? CollectionSpec spec) (map? s))
      (reduce (fn [acc [k v]]
                (assoc acc (key-fn (s/explicit-schema-key k)) (walk-schema v key-fn leaf-fn)))
              {} s)
      (and (instance? CollectionSpec spec) (sequential? s))
      (reduce (fn [acc v]
                (conj acc (walk-schema v key-fn leaf-fn)))
              [] (or s [])))))

(defn get-schema-keyseqs [schema]
  (->> (walk-schema schema identity #(do % 1))
       flatten-structure
       (map first)))

(deftype FieldMeta [metadata])

(defn get-schema-relations
  "this function gets a schema as its input and returns a map of a Specter path to the corresponding relation spec"
  [schema]
  (let [relations (volatile! {})]                           ; No need for the atom atomicity guarantees here
    (doseq [[path metadata] (flatten-structure (walk-schema schema identity #(FieldMeta. (meta %))))
            :let [relation-spec (:grape/relation-spec (.-metadata metadata))
                  specter-path (into [] (map #(if (vector? %) ALL %) path))]
            :when relation-spec]
      (vswap! relations assoc specter-path relation-spec))
    @relations))

(defn get-schema-types-ks [schema]
  (let [relations (volatile! {})]                           ; No need for the atom atomicity guarantees here
    (doseq [[path metadata] (flatten-structure (walk-schema schema identity #(FieldMeta. (meta %))))
            :let [type-spec (:grape/type (.-metadata metadata))]
            :when type-spec]
      (vswap! relations assoc path type-spec))
    @relations))

(defn get-schema-types [schema]
  (reduce (fn [acc [ks v]] (assoc-in acc ks v))
          {}
          (get-schema-types-ks schema)))