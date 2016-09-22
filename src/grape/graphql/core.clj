(ns grape.graphql.core
  (:require [schema.core :as s :refer [explain]]
            [schema.spec.variant :as variant]
            [schema.spec.core :as spec]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f]
            [grape.schema :refer :all])
  (:import (graphql.schema GraphQLObjectType GraphQLSchema)
           (graphql.schema GraphQLFieldDefinition GraphQLObjectType GraphQLArgument GraphQLInterfaceType TypeResolver DataFetcher GraphQLEnumType GraphQLEnumValueDefinition GraphQLNonNull GraphQLList GraphQLTypeReference DataFetchingEnvironment GraphQLInputObjectType GraphQLInputObjectField GraphQLUnionType GraphQLScalarType Coercing)
           (graphql Scalars GraphQL)
           (java.util Map List)
           (graphql.execution.batched BatchedExecutionStrategy BatchedDataFetcher)
           (graphql.relay Relay)
           (clojure.lang IRecord)
           (schema.core Schema Maybe)
           (org.bson.types ObjectId)
           (graphql.language StringValue IntValue)
           (org.joda.time DateTime)))

(defn enum-value [name value & {:keys [description deprecation-reason]}]
  (GraphQLEnumValueDefinition. name description value deprecation-reason))
(defn enum [name values & {:keys [description]}]
  (GraphQLEnumType. name description values))

(defn argument [name type & {:keys [description default-value]}]
  (cond-> (GraphQLArgument/newArgument)
          true (.name name)
          true (.type type)
          description (.description description)
          default-value (.defaultValue default-value)
          true (.build)))

(defn field [name type & {:keys [description ^DataFetcher data-fetcher static-value deprecation-reason arguments]}]
  (cond-> (GraphQLFieldDefinition/newFieldDefinition)
          true (.type type)
          true (.name name)
          description (.description description)
          data-fetcher (.dataFetcher data-fetcher)
          static-value (.staticValue static-value)
          deprecation-reason (.deprecate deprecation-reason)
          arguments (.argument arguments)
          true (.build)))

(defn interface [name fields ^TypeResolver type-resolver & {:keys [description]}]
  (cond-> (GraphQLInterfaceType/newInterface)
          true (.name name)
          true (.fields fields)
          true (.typeResolver type-resolver)
          description (.description description)
          true (.build)))

(defn union [name ^TypeResolver types type-resolver & {:keys [description]}]
  (cond-> (GraphQLUnionType/newUnionType)
          true (.name name)
          true (.typeResolver type-resolver)
          true (.possibleTypes (into-array GraphQLObjectType types))
          description (.description description)
          true (.build)))

(defn object [name fields & {:keys [description interfaces]}]
  (cond-> (GraphQLObjectType/newObject)
          true (.name name)
          true (.fields fields)
          description (.description description)
          interfaces (.withInterfaces (into-array GraphQLInterfaceType interfaces))
          true (.build)))

(defn input-field [name type & {:keys [description default-value]}]
  (cond-> (GraphQLInputObjectField/newInputObjectField)
          true (.name name)
          true (.type type)
          description (.description description)
          default-value (.defaultValue default-value)
          true (.build)))

(defn input-object [name fields & {:keys [description]}]
  (cond-> (GraphQLInputObjectType/newInputObject)
          true (.name name)
          true (.fields fields)
          description (.description description)
          true (.build)))

(defn schema [^GraphQLObjectType query & {:keys [^GraphQLObjectType mutation]}]
  (cond-> (GraphQLSchema/newSchema)
          true (.query query)
          mutation (.mutation mutation)
          true (.build)))

(defn type-resolver [f]
  (reify TypeResolver
    (getType [this object]
      (f object))))

(defn data-fetcher [f]
  (reify DataFetcher
    (get [this ^DataFetchingEnvironment environment]
      (f environment))))

(defn batched-data-fetcher [f]
  (reify BatchedDataFetcher
    (get [this ^DataFetchingEnvironment environment]
      (f environment))))

(defn to-clj [data]
  (cond
    (instance? Map data)
    (->> data
         (into {})
         (map (fn [[k v]] [k (to-clj v)]))
         (into {}))
    (instance? List data)
    (->> data
         (map #(to-clj %))
         (into []))
    :else
    data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Relay
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def relay (Relay.))

(defn connection [name type type-resolver & {:keys [data-fetcher]}]
  (let [node-interface (.nodeInterface relay type-resolver)
        edge (.edgeType relay name type node-interface [])]
    (.connectionType relay name edge [])))

(defn connection-data-fetcher [f]
  (data-fetcher
    (fn [^DataFetchingEnvironment env]
      (let [limit (or (.getArgument env "first") 50)
            ;b64-decode (fn [str]
            ;             (String. (.decode (Base64/getDecoder) (.getBytes str "UTF-8")) "UTF-8"))
            ;b64-encode (fn [str]
            ;             (String. (.encode (Base64/getEncoder) (.getBytes str "UTF-8")) "UTF-8"))
            skip (or
                   (if-let [after (.getArgument env "after")]
                     (-> after read-string))
                   0)
            {:keys [_count _documents]} (f skip limit env)]
        {"pageInfo" {"hasNextPage" (> _count (count _documents))}
         "edges"    (map-indexed
                      (fn [i doc]
                        {"cursor" (str (+ i skip 1))
                         "node"   doc})
                      _documents)}))))

(def connection-args
  (.getConnectionFieldArguments relay))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute [^GraphQLSchema schema ^Object context ^String q & [^Map params]]
  (let [result (cond-> (GraphQL. schema (BatchedExecutionStrategy.))
                       params (.execute q nil context params)
                       (not params) (.execute q context))]
    (if-let [data (.getData result)]
      (to-clj data)
      (do (prn "ERROR")
          (to-clj (.getErrors result))))))

(def ScalarDateTime
  (GraphQLScalarType. "DateTime" "date time string or timestamp"
                      (reify Coercing
                        (serialize [this ^Object input]
                          (condp instance? input
                            DateTime input
                            String (let [formatter (f/formatters :date-time)]
                                     (f/parse formatter input))
                            Long (c/from-long input)
                            Integer (c/from-long (long input))))
                        (parseValue [this ^Object input]
                          (.serialize this input))
                        (parseLiteral [this ^Object input]
                          (cond
                            (instance? StringValue input) (let [formatter (f/formatters :date-time)]
                                                            (f/parse formatter (.getValue input)))
                            (instance? IntValue input) (c/from-long (long (.getValue input))))))))

(def ScalarObjectId
  (GraphQLScalarType. "ObjectId" "mongodb object id"
                      (reify Coercing
                        (serialize [this ^Object input]
                          (condp instance? input
                            String (ObjectId. ^String input)
                            ObjectId input))
                        (parseValue [this ^Object input]
                          (.serialize this input))
                        (parseLiteral [this ^Object input]
                          (cond
                            (instance? StringValue input) (ObjectId. (.getValue input)))))))

(def types-map
  {ObjectId ScalarObjectId
   DateTime ScalarDateTime
   s/Num    Scalars/GraphQLFloat
   s/Int    Scalars/GraphQLInt
   s/Bool   Scalars/GraphQLBoolean
   s/Str    Scalars/GraphQLString})

(defn prismatic->graphql-schema [type-name schema
                                 & {:keys [skip-hidden? skip-read-only?]
                                    :or {skip-hidden? false skip-read-only? false}}]
  (walk-schema
    schema
    (comp name #(if (= % :_id) :id %) schema.core/explicit-schema-key)
    (fn [path v]
      (types-map
        (if (maybe? v) (:schema v) v)))
    :transform-map (fn [path m]
                     (object (name (gensym type-name))
                             (map (fn [[k v]] (field k v)) m)))
    :transform-seq (fn [path v]
                     (prn path v)
                     (if (seq (filter identity v))
                       (GraphQLList. (first v))
                       v))
    :skip-hidden? skip-hidden?
    :skip-read-only? skip-read-only?
    :skip-unwrap-for (fn [path v]
                       (and (maybe? v) (primitive? (:schema v))))))