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
            [schema-tools.core.impl :as stc-impl])
  (:import (clojure.lang ExceptionInfo ISeq)
           (schema.utils ValidationError)
           (schema.core Predicate Constrained Maybe Both)
           (java.util UnknownFormatConversionException)
           (java.util.regex Pattern)
           (schema.spec.leaf LeafSpec)
           (schema.spec.collection CollectionSpec)
           (schema.spec.variant VariantSpec)))

(def ^:dynamic *deps* {})
(def ^:dynamic *resource* {})
(def ^:dynamic *request* {})

(def errors-translations
  {:en {"missing-required-key"             "the field is required"
        "disallowed-key"                   "extra fields not allowed"
        "type-invalid"                     "invalid type"
        #"type-should-be-(.*)"             "The field should be of type %s"
        "url-should-be-valid"              "the url should be valid"
        "read-only"                        "the field is read-only"
        "resource-should-exist"            "the resource should exist"
        "email-should-be-valid"            "the email should be valid"
        #"length-([0-9]+)-([0-9]+)"        "field length should be between %s and %s characters long"
        #"value-should-be-one-of-(.*)"     "the value should be one of %s"
        "field-should-be-positive-integer" "the field should be a positive integer"}
   :fr {"missing-required-key"             "le champ est requis"
        "disallowed-key"                   "les champs supplémentaires ne sont pas autorisés"
        "type-invalid"                     "type invalide"
        #"type-should-be-(.*)"             "Le champ doit être de type %s"
        "url-should-be-valid"              "L'url doit être valide"
        "read-only"                        "le champ est en lecture seule"
        "resource-should-exist"            "la ressource doit exister"
        "email-should-be-valid"            "l'email doit être valide"
        #"length-([0-9]+)-([0-9]+)"        "le champ doit faire entre %s et %s caractères"
        #"value-should-be-one-of-(.*)"     "la valeur doit être l'une des valeurs suivantes : %s"
        "field-should-be-positive-integer" "le champ doit être un entier positif"}})

(defn translate-error [err]
  (let [{:keys [config translations]} *deps*
        default-lang (:default-language config :en)
        lang (keyword (get-in *request* [:accept :language] default-lang))
        translations (deep-merge errors-translations (or translations {}))
        err (str err)
        found (->> (lang translations)
                   (filter (fn [[k _]]
                             (if (instance? Pattern k)
                               (re-matches k err)
                               (= k err))))
                   (map (fn [[k v]]
                          (if (instance? Pattern k)
                            [v (rest (re-matches k err))]
                            v)))
                   first)]
    (if found
      (try
        (if (string? found)
          found
          (apply
            (partial format (first found))
            (second found)))
        (catch UnknownFormatConversionException _
          err))
      err)))

;; Here we redefine schema primitives using predicates instead of raw types to be able to attach
;; Clojure metadata to fields (useful for relationship declaration)
;; TODO ClojureScript compat'
(def Int (vary-meta (s/pred integer? "type-should-be-integer") assoc :grape/type 'Int))
(def Num (vary-meta (s/pred number? "type-should-be-number") assoc :grape/type 'Num))
(def Str (vary-meta (s/pred string? "type-should-be-string") assoc :grape/type 'Str))
(def Bool (vary-meta (s/pred #(instance? Boolean %) "type-should-be-boolean") assoc :grape/type 'Bool))
(def DateTime (vary-meta (s/pred #(instance? org.joda.time.DateTime %) "type-should-be-date-time") assoc :grape/type 'DateTime))
(def ObjectId (vary-meta (s/pred #(instance? org.bson.types.ObjectId %) "type-should-be-object-id") assoc :grape/type 'ObjectId))
(def Any (vary-meta (s/pred (constantly true) "type-should-be-any") assoc :grape/type 'Any))

(defn Field
  ([type]
   type)
  ([type ^ISeq validation-pairs]
   (vary-meta (apply s/both (map #(Field type (first %) (second %)) validation-pairs)) merge (meta type)))
  ([type valid? ^String error-key]
   (vary-meta (s/constrained type valid? error-key) merge (meta type))))

(defn resource-exists? [resource-key]
  (fn [id]
    (let [resource (resource-key (:resources-registry *deps*))
          deps-store (:store *deps*)
          source (get-in resource [:datasource :source])]
      (try
        (> (store/count deps-store source {:find {:_id id}} {}) 0)
        (catch Exception _ false)))))

(defn ResourceField [id-schema resource-key]
  (vary-meta
    (Field id-schema (resource-exists? resource-key) "resource-should-exist")
    merge {:grape/relation-spec {:type     :embedded
                                 :resource resource-key}}))

(defn ResourceJoin
  ([resource-key resource-field]
   (vary-meta
     Any
     merge {:grape/relation-spec
            {:type     :join
             :resource resource-key
             :field    resource-field}}))
  ([resource-key resource-field type]
   (vary-meta
     (ResourceJoin resource-key resource-field)
     merge {:grape/type type})))

(def email-valid?
  (partial re-matches #"^[^@]+@[^@\\.]+[\\.].+"))

(def EmailField (Field String email-valid? "email-should-be-valid"))

(defrecord ReadOnly [schema]
  s/Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (constantly false) (fn [_] "this key is read-only"))))
  (explain [this] (list 'read-only (s/explain schema))))
(defn read-only [schema] (->ReadOnly schema))

(defrecord WriteOnly [schema]
  s/Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (constantly true) (fn [_] ""))))
  (explain [this] (list 'write-only (s/explain schema))))
(defn write-only [schema] (->WriteOnly schema))

(def ? s/optional-key)

(def maybe s/maybe)

(defn SizedField [type min max]
  (Field type #(<= min (count %) max) (str "length-" min "-" max)))

(def PositiveInt
  (Field Int #(>= % 0) (str "field-should-be-positive-integer")))

(def StrictPositiveInt
  (Field Int #(> % 0) (str "field-should-be-strict-positive-integer")))

(def url-valid?
  (partial re-matches #"^https?:\/\/(?:(?!-)[a-zA-Z0-9-]{1,63}(?<!-)\.)+[a-zA-Z]{2,63}(?:\:[0-9]{2,5})?(?:\/[a-zA-Z0-9\/%@!?$&|\'()*+,#;=.~_-]*)?$"))

(def UrlField (Field String url-valid? "url-should-be-valid"))

(defn EnumField [type enum-set]
  (Field type (into #{} enum-set) (str "value-should-be-one-of-" (clojure.string/join ", " enum-set))))

(def default stc-impl/default)

(def object-id-matcher
  {ObjectId (coerce/safe #(object-id ^String %))})

(def date-time-matcher
  (let [date-formatter (get-in *deps* [:config :date-formatter] (f/formatters :date-time))]
    {DateTime (coerce/safe #(f/parse date-formatter))}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn walk-schema [s key-fn leaf-fn]
  (let [s (cond
            (instance? Maybe s)
            (:schema s)
            (instance? ReadOnly s)
            (:schema s)
            (instance? WriteOnly s)
            (with-meta (:schema s) {:grape/write-only true})
            (instance? Both s)
            (first (.-schemas s))
            :else
            s)
        spec (s/spec s)]
    (cond
      (or (instance? LeafSpec spec) (instance? VariantSpec spec))
      (leaf-fn s)
      (and (instance? CollectionSpec spec) (map? s))
      (reduce (fn [acc [k v]]
                (assoc acc (key-fn k) (walk-schema v key-fn leaf-fn)))
              {} s)
      (and (instance? CollectionSpec spec) (sequential? s))
      (reduce (fn [acc v]
                (conj acc (walk-schema v key-fn leaf-fn)))
              [] (or s [])))))

(defn get-schema-keyseqs [schema]
  (->> (walk-schema schema s/explicit-schema-key (constantly 1))
       flatten-structure
       (map first)))

(deftype SchemaWrapper [schema])
(defn flatten-schema [schema]
  (->> (walk-schema schema s/explicit-schema-key (fn [s] (SchemaWrapper. s)))
       flatten-structure
       (map #(vector (first %) (.-schema (second %))))))

(deftype FieldMeta [metadata])

(defn get-schema-relations
  "this function gets a schema as its input and returns a map of a Specter path to the corresponding relation spec"
  [schema]
  (let [relations (volatile! {})]                           ; No need for the atom atomicity guarantees here
    (doseq [[path metadata] (flatten-structure (walk-schema schema s/explicit-schema-key #(FieldMeta. (meta %))))
            :let [relation-spec (:grape/relation-spec (.-metadata metadata))
                  path (into [] path)
                  ;; the user should be able to specify the relations using the flatten syntax "comments" and not "comments.[]"
                  spec-path (into [] (filter (partial not= []) path))]
            :when relation-spec]
      ;; when relation is an embedded there is no restriction for defining the relation in embedded fields or in arrays
      ;; but when it's a join, array are not authorized except for wrapping the join (corresponds to a join many)
      (when (= (:type relation-spec) :join)
        (assert (empty? (->> path
                             drop-last
                             (filter sequential?)))
                "schema error: relation spec join in an object having a parent array in not supported"))
      (vswap! relations assoc path (assoc relation-spec :path spec-path)))
    @relations))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cleve-coercion-matcher [schema]
  (or (stc/default-coercion-matcher schema)
      (object-id-matcher schema)
      (date-time-matcher schema)
      (coerce/json-coercion-matcher schema)))

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
            *request* request]
    (validate payload (:schema resource))))

(defn validate-update [{:keys [hooks] :as deps} resource request payload]
  (binding [*deps* deps
            *resource* resource
            *request* request]
    (validate payload (:schema resource))))

(defn validate-partial-update [{:keys [hooks] :as deps} resource request payload]
  (binding [*deps* deps
            *resource* resource
            *request* request]
    ;; for partial update, the schema should have all of its keys optional
    (let [schema (walk-schema (:schema resource) #(s/optional-key (s/explicit-schema-key %)) identity)]
      (validate payload schema))))
