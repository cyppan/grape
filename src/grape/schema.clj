(ns grape.schema
  (:require [schema.core :as s]
            [schema.spec.core :as spec]
            [schema.spec.leaf :as leaf]
            [grape.utils :refer :all]
            [slingshot.slingshot :refer [throw+ try+]]
            [grape.store :as store]
            [schema-tools.core :as st]
            [schema-tools.coerce :as stc]
            [schema.coerce :as coerce]
            [monger.util :refer [object-id]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.tools.logging :refer [log]]
            [schema-tools.core.impl :as stc-impl]
            [schema.spec.variant :as variant]
            [com.rpl.specter :refer [ALL]]
            )
  (:import (schema.utils ValidationError)
           (schema.core Predicate Constrained Schema)
           (org.bson.types ObjectId)
           (org.joda.time DateTime)))

(def ^:dynamic *deps* {})
(def ^:dynamic *resource* {})
(def ^:dynamic *request* {})
(def ^:dynamic *payload* {})
(def ^:dynamic *existing* {})

(def ? s/optional-key)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; VALIDATORS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resource-exists? [resource-key]
  (fn [id]
    (let [resource (resource-key (:resources-registry *deps*))
          deps-store (:store *deps*)
          source (get-in resource [:datasource :source])]
      (pos? (store/count deps-store source
                         {:find {:_id id}}
                         {:soft-delete? (:soft-delete *resource*)})))))

(defn unique? [field]
  (fn [item]
    (let [deps-store (:store *deps*)
          source (get-in *resource* [:datasource :source])]
      (zero? (store/count deps-store source
                          {:find (merge {field item}
                                        (when-let [id (:_id *existing*)]
                                          {:_id {:$ne id}}))}
                          {:soft-delete? (:soft-delete *resource*)})))))

(defn insensitive-unique? [field]
  (fn [item]
    (let [deps-store (:store *deps*)
          source (get-in *resource* [:datasource :source])]
      (zero? (store/count deps-store source
                          {:find (merge {field {:$regex (str "^" item "$") :$options "i"}}
                                        (when-let [id (:_id *existing*)]
                                          {:_id {:$ne id}}))}
                          {:soft-delete? (:soft-delete *resource*)})))))

(def email?
  (partial re-matches #"^[^@]+@[^@\\.]+[\\.].+"))


(def url?
  (partial re-matches #"^https?:\/\/(?:(?!-)[a-zA-Z0-9-]{1,63}(?<!-)\.)+[a-zA-Z]{2,63}(?:\:[0-9]{2,5})?(?:\/[a-zA-Z0-9\/%@!?$&|\'()*+,#;=.~_-]*)?$"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; SCHEMA VARIANTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord ResourceEmbedded [resource-key field schema]
  Schema
  (spec [this]
    (variant/variant-spec
      spec/+no-precondition+
      [{:schema schema}]))
  (explain [this] (list 'resource-embedded resource-key)))

(defn resource-embedded [resource-key field schema]
  (s/constrained (->ResourceEmbedded resource-key field schema) (resource-exists? resource-key) "resource-should-exist"))

(defrecord ResourceJoin [resource-key field schema]
  Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (constantly false) (fn [_] "this key is read-only"))))
  (explain [this] (list 'resource-join resource-key field)))

(defn resource-join [resource-key field]
  (->ResourceJoin resource-key field s/Any))

(defrecord ReadOnly [schema]
  s/Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (constantly false) (fn [_] "this key is read-only"))))
  (explain [this] (list 'read-only (s/explain schema))))

(defn read-only [schema] (->ReadOnly schema))

(defrecord Hidden [schema]
  s/Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (constantly true) (fn [_] ""))))
  (explain [this] (list 'hidden (s/explain schema))))

(defn hidden [schema] (->Hidden schema))

(def default stc-impl/default)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def primitive? #{s/Int s/Num s/Bool s/Str s/Any ObjectId DateTime})
(def maybe? (partial instance? schema.core.Maybe))
(def hidden? (partial instance? Hidden))
(def read-only? (partial instance? ReadOnly))
(def resource-embedded? (partial instance? ResourceEmbedded))
(def resource-join? (partial instance? ResourceJoin))

(defn walk-schema [schema key-fn value-fn
                   & {:keys [skip-hidden? skip-read-only? skip-unwrap-for transform-map transform-seq]
                      :or   {skip-hidden?  false skip-read-only? false skip-unwrap-for (fn [path v] false)
                             transform-map (fn [path v] v) transform-seq (fn [path v] v)}
                      :as   args}]
  ((fn walk [path v]
     (cond
       (or (primitive? v) (skip-unwrap-for path v))
       (value-fn path v)

       (and (record? v) (seq (:schemas v)))
       (walk path (first (:schemas v)))

       (record? v)
       (walk path (:schema v))

       (map? v)
       (->> v
            (filter (fn [[k v]]
                      (when-not (or (and skip-hidden? (hidden? v))
                                    (and skip-read-only? (or (read-only? v) (resource-join? v))))
                        [k v])))
            (map (fn [[k v]]
                   (let [k (key-fn k)]
                     [k (walk (conj path k) v)])))
            (into {})
            (transform-map path))

       (sequential? v)
       (let [walked (walk (conj path []) (first v))]
         (transform-seq path [walked]))))
    [] schema))

(defn get-schema-keyseqs [schema & {:keys [skip-hidden? skip-read-only?]}]
  (let [keyseqs (volatile! #{})]
    (walk-schema schema s/explicit-schema-key (fn [path _]
                                                (vswap! keyseqs conj path)
                                                nil)
                 :skip-hidden? skip-hidden?
                 :skip-read-only? skip-read-only?
                 :transform-map (fn [path m]
                                  (when (seq m)
                                    (vswap! keyseqs conj path))
                                  m)
                 :transform-seq (fn [path v]
                                  (when (seq v)
                                    (vswap! keyseqs conj path))
                                  v))
    (into #{} (filter seq @keyseqs))))

(defn get-schema-relations
  "this function gets a schema as its input and returns a map of a Specter path to the corresponding relation spec"
  [schema]
  (let [relations (volatile! {})]
    (walk-schema schema
                 s/explicit-schema-key
                 (fn [path value]
                   (assert (not (and (resource-join? value)
                                     (seq (->> path drop-last (filter sequential?)))))
                           "schema error: relation spec join in an object having a parent array in not supported")
                   (cond
                     (resource-embedded? value)
                     (vswap! relations assoc path {:type :embedded :resource (:resource-key value)})

                     (resource-join? value)
                     (vswap! relations assoc path {:type :join :resource (:resource-key value) :field (:field value)}))
                   value)
                 :skip-unwrap-for (fn [path v]
                                    (or (resource-embedded? v)
                                        (resource-join? v))))
    (->> @relations
         (map (fn [[k v]]
                [(mapv #(if (= % []) ALL (keyword %)) k) v]))
         (into {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def object-id-matcher
  {ObjectId (coerce/safe #(object-id ^String %))})

(def date-time-matcher
  (let [date-formatter (get-in *deps* [:config :date-formatter] (f/formatters :date-time))]
    {DateTime (coerce/safe #(f/parse date-formatter %))}))

(defn cleve-coercion-matcher [schema]
  (or (stc/default-coercion-matcher schema)
      (object-id-matcher schema)
      (date-time-matcher schema)
      (coerce/json-coercion-matcher schema)))

(defn translate-error [err] err)

(defn map-validation-error [error]
  {:type :validation-failed
   :error
         (clojure.walk/postwalk
           (fn [el]
             (cond
               (and (instance? ValidationError el) (= (type (.-schema el)) java.lang.Class))
               (translate-error "type-invalid")
               (and (instance? ValidationError el)
                    (= (.-fail-explanation el) 'read-only))
               (translate-error "read-only")
               (and (instance? ValidationError el)
                    (instance? Predicate (.-schema el)))
               (translate-error (:pred-name (.-schema el)))
               (and (instance? ValidationError el)
                    (instance? Constrained (.-schema el)))
               (if (vector? (:post-name (.-schema el)))
                 (translate-error (second (:post-name (.-schema el))))
                 (translate-error (:post-name (.-schema el))))
               (symbol? el)
               (translate-error (str el))
               (and (instance? ValidationError el)
                    (= (.-fail-explanation el) 'not))
               (str (.-schema el))
               (instance? ValidationError el)
               (s/explain (.-schema el))
               :else el))
           error)})

(defn validate [payload schema]
  (try+
    (stc/coerce payload schema cleve-coercion-matcher)
    (catch [:type :schema-tools.coerce/error] {:keys [error]}
      (throw (ex-info "validation failed" (map-validation-error error))))))

(defn validate-create [{:keys [hooks] :as deps} resource request payload]
  (binding [*deps* deps
            *resource* resource
            *request* request
            *payload* payload]
    (validate payload (:schema resource))))

(defn validate-update [{:keys [hooks] :as deps} resource request payload existing]
  (binding [*deps* deps
            *resource* resource
            *request* request
            *payload* payload
            *existing* existing]
    (validate payload (:schema resource))))

(defn validate-partial-update [{:keys [hooks] :as deps} resource request payload existing]
  (binding [*deps* deps
            *resource* resource
            *request* request
            *payload* payload
            *existing* existing]
    ;; for partial update, the schema should have all of its keys optional
    (let [schema (st/optional-keys (:schema resource))]
      (validate payload schema))))
