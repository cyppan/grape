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
            [grape.hooks.core :refer [compose-hooks]]
            [taoensso.timbre :refer [log]])
  (:import (org.bson.types ObjectId)
           (org.joda.time DateTime)
           (clojure.lang ExceptionInfo)
           (schema.utils ValidationError)
           (schema.core Predicate Constrained)
           (java.util UnknownFormatConversionException)
           (java.util.regex Pattern)))

(def ^:dynamic *deps* {})
(def ^:dynamic *resource* {})
(def ^:dynamic *request* {})

(def errors-translations
  {:en {"missing-required-key" "the field is required"
        "disallowed-key"       "extra fields not allowed"
        "type-invalid"         "invalid type"
        "read-only"            "the field is read-only"
        "resource-exists"      "the resource should exist"
        #"min-length-([0-9]+)" "minimum length is %s"
        #"max-length-([0-9]+)" "maximum length is %s"}
   :fr {"missing-required-key" "le champ est requis"
        "disallowed-key"       "les champs supplémentaires ne sont pas autorisés"
        "type-invalid"         "type invalide"
        "read-only"            "le champ est en lecture seule"
        "resource-exists"      "la ressource doit exister"
        #"min-length-([0-9]+)" "la longueur minimum est %s"
        #"max-length-([0-9]+)" "la longueur maximum est %s"}})

(defn translate-error [err]
  (let [{:keys [config translations]} *deps*
        default-lang (:default-language config :en)
        lang (keyword (get-in *request* [:accept :language] default-lang))
        translations (deep-merge errors-translations (or translations {}))
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

(defn resource-exists [resource-key]
  (fn [id]
    (let [resource (resource-key (:resources-registry *deps*))
          deps-store (:store *deps*)
          source (get-in resource [:datasource :source])]
      (try
        (boolean
          (store/count deps-store source {:find {:_id id}} {}))
        (catch Exception _ false)))))

(defrecord ReadOnly [schema]
  s/Schema
  (spec [this]
    (leaf/leaf-spec
      (spec/precondition this (fn [_] false) (fn [_] "this key is read-only"))))
  (explain [this] (list 'read-only (s/explain schema))))
(defn read-only [schema] (->ReadOnly schema))

(def ? s/optional-key)

(defn Str [min max]
  (s/constrained s/Str
                 #(<= min (count %) max)
                 (str "str-length-" min "-" max)))

(def Url #"^https?:\/\/(?:(?!-)[a-zA-Z0-9-]{1,63}(?<!-)\.)+[a-zA-Z]{2,63}(?:\:[0-9]{2,5})?(?:\/[a-zA-Z0-9\/%@!?$&\'()*+,#;=.~_-]*)?$")

(def object-id-matcher
  {ObjectId (coerce/safe #(object-id ^String %))})

(def date-time-matcher
  (let [date-formatter (get-in *deps* [:config :date-formatter] (f/formatters :date-time))]
    {DateTime (coerce/safe #(f/parse date-formatter))}))

(defn cleve-coercion-matcher [schema]
  (or (stc/default-coercion-matcher schema)
      (object-id-matcher schema)
      (date-time-matcher schema)
      (coerce/json-coercion-matcher schema)))

(defn map-validation-error [error]
  {:type ::error
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
              (= (.-fail-explanation el) 'not)
              (instance? Predicate (.-schema el)))
         (translate-error (:pred-name (.-schema el)))
         (and (instance? ValidationError el)
              (= (.-fail-explanation el) 'not)
              (instance? Constrained (.-schema el)))
         (translate-error (:post-name (.-schema el)))
         (symbol? el)
         (translate-error (str el))
         :else el))
     error)})

(defn validate-create [{:keys [hooks] :as deps} resource request payload]
  (binding [*deps* deps
            *resource* resource
            *request* request]
    (let [hooks (compose-hooks hooks resource)
          pre-validate-fn (:pre-create-pre-validate hooks)
          payload (pre-validate-fn deps resource request payload)]
      (try+
        (stc/coerce payload (:schema resource) cleve-coercion-matcher)
        (catch [:type :schema-tools.coerce/error] {:keys [error]}
          (throw (ex-info "validation-create failed" (map-validation-error error))))))))

(defn validate-update [{:keys [hooks] :as deps} resource request payload existing]
  (binding [*deps* deps
            *resource* resource
            *request* request]
    (let [hooks (compose-hooks hooks resource)
          pre-validate-fn (:pre-update-pre-validate hooks)
          payload (pre-validate-fn deps resource request payload existing)]
      (try+
        (stc/coerce payload (:schema resource) cleve-coercion-matcher)
        (catch [:type :schema-tools.coerce/error] {:keys [error]}
          (throw (ex-info "validation-update failed" (map-validation-error error))))))))

(defn validate-partial-update [{:keys [hooks] :as deps} resource request payload existing]
  (binding [*deps* deps
            *resource* resource
            *request* request]
    (let [hooks (compose-hooks hooks resource)
          pre-validate-fn (:pre-update-pre-validate hooks)
          payload (pre-validate-fn deps resource request payload existing)
          ;; for partial update, the schema should have all of its keys optional
          schema (walk-schema (:schema resource) #(s/optional-key (s/explicit-schema-key %)) identity)]
      (try+
        (stc/coerce payload schema cleve-coercion-matcher)
        (catch [:type :schema-tools.coerce/error] {:keys [error]}
          (throw (ex-info "validation-partial-update failed" (map-validation-error error))))))))
