(ns grappe.schema
  (:require [schema.core :as s]
            [schema.spec.core :as spec]
            [schema.spec.leaf :as leaf]
            [grappe.utils :refer :all])
  (:import (schema.spec.leaf LeafSpec)
           (schema.spec.variant VariantSpec)
           (schema.spec.collection CollectionSpec)))

(defrecord ReadOnly [schema]
  s/Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (fn [_] false) (fn [_] "this key is read-only"))))
  (explain [this] (list 'read-only (s/explain schema))))
(defn read-only [schema] (->ReadOnly schema))

(def ? s/optional-key)

(def every s/both)

(defn min-length [n]
  (s/pred #(>= (count %) n) (str "min length " n)))

(defn max-length [n]
  (s/pred #(<= (count %) n) (str "max length " n)))

(defn Str [min max]
  (every s/Str (min-length min) (max-length max)))

(def Url #"^https?:\/\/(?:(?!-)[a-zA-Z0-9-]{1,63}(?<!-)\.)+[a-zA-Z]{2,63}(?:\:[0-9]{2,5})?(?:\/[a-zA-Z0-9\/%@!?$&\'()*+,#;=.~_-]*)?$")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn walk-schema [s key-fn leaf-fn]
  (let [spec (s/spec s)]
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
