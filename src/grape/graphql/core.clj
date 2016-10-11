(ns grape.graphql.core
  (:require [schema.core :as s :refer [explain]]
            [schema.spec.variant :as variant]
            [schema.spec.core :as spec]
            [cheshire.core :refer [parse-string]]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f]
            [grape.schema :refer :all]
            [grape.core :refer [read-resource read-item]]
            [grape.utils :refer [->PascalCase]]
            [clojure.tools.logging :as log])
  (:import (graphql.schema GraphQLObjectType GraphQLSchema)
           (graphql.schema GraphQLFieldDefinition GraphQLObjectType GraphQLArgument GraphQLInterfaceType TypeResolver DataFetcher GraphQLEnumType GraphQLEnumValueDefinition GraphQLNonNull GraphQLList GraphQLTypeReference DataFetchingEnvironment GraphQLInputObjectType GraphQLInputObjectField GraphQLUnionType GraphQLScalarType Coercing)
           (graphql Scalars GraphQL)
           (java.util Map List)
           (graphql.execution.batched BatchedExecutionStrategy BatchedDataFetcher)
           (graphql.relay Relay)
           (org.bson.types ObjectId)
           (graphql.language StringValue IntValue)
           (org.joda.time DateTime)
           (com.fasterxml.jackson.core JsonParseException)))

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
    (get [this ^DataFetchingEnvironment env]
      ;(prn "calling data-fetcher")
      (try
        (let [[deps request] (.getContext env)]
          (f deps request env))
        (catch Exception ex
          (log/error ex))))))

(defn batched-data-fetcher [f]
  (reify BatchedDataFetcher
    (get [this ^DataFetchingEnvironment env]
      ;(prn "calling batched-data-fetcher")
      (try
        (let [[deps request] (.getContext env)]
          (f deps request env))
        (catch Exception ex
          (log/error ex))))))

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

(defn mongo->graphql [type]
  (fn [obj]
    (-> obj
        (clojure.set/rename-keys {:_id :id})
        (assoc :_type type)
        clojure.walk/stringify-keys)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Relay
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def relay (Relay.))

(defn connection [name type type-resolver]
  (let [node-interface (.nodeInterface relay type-resolver)
        edge (.edgeType relay name type node-interface [])]
    (.connectionType relay name edge [])))

(defn connection-data-fetcher [f]
  (data-fetcher
    (fn [deps request ^DataFetchingEnvironment env]
      (let [limit (or (.getArgument env "first") 50)
            ;b64-decode (fn [str]
            ;             (String. (.decode (Base64/getDecoder) (.getBytes str "UTF-8")) "UTF-8"))
            ;b64-encode (fn [str]
            ;             (String. (.encode (Base64/getEncoder) (.getBytes str "UTF-8")) "UTF-8"))
            skip (or
                   (if-let [after (.getArgument env "after")]
                     (-> after read-string))
                   0)
            find (or
                   (if-let [find (.getArgument env "find")]
                     (try
                       (parse-string find)
                       (catch JsonParseException _ {})))
                   {})
            sort (if-let [sort (.getArgument env "sort")]
                   (->> (clojure.string/split sort #",")
                        (map (fn [field]
                               (if (.startsWith field "-")
                                 [(keyword (clojure.string/replace-first field "-" "")) -1]
                                 [(keyword field) 1])))
                        flatten
                        (apply array-map)))
            query {:find     find
                   :sort     sort
                   :paginate {:skip skip :limit limit}
                   :opts     {:count? true :paginate? true :sort? true}}
            {:keys [_count _documents]} (f deps request query env)]
        {"pageInfo" {"hasNextPage" (< (+ skip limit) _count)}
         "edges"    (map-indexed
                      (fn [i doc]
                        {"cursor" (str (+ i skip 1))
                         "node"   doc})
                      _documents)}))))

(defn type-ref-data-fetcher [^grape.graphql.GrapeTypeRef grape-type-ref & {:keys [many?]}]
  (let [[name type resource-key field] (.state grape-type-ref)]
    (batched-data-fetcher
      (fn [{:keys [resources-registry] :as deps} request ^DataFetchingEnvironment env]
        (let [source (.getSource env)
              resource (get resources-registry resource-key)
              _type (:grape.graphql/type resource)
              fields (mapv #(.getName %) (.getSelections (.getSelectionSet (first (.getFields env)))))]

          (cond
            (= type :join)
            (let [ids (->> source (map #(get % "id")) distinct)
                  to-join (read-resource deps resource request {:find   {field {:$in ids} :_deleted {:$ne true}}
                                                                :fields (distinct (conj fields field))})]
              (->> source
                   (map #(get % "id"))
                   (map (fn [id]
                          (cond->> (:_documents to-join)
                                   true (filter #(= id (field %)))
                                   true (map (mongo->graphql _type))
                                   (not many?) first)))))

            (= type :embedded)
            (let [ids (->> source (map #(get % (clojure.core/name field))) flatten distinct)
                  to-join (read-resource deps resource request {:find   {:_id {:$in ids} :_deleted {:$ne true}}
                                                                :fields (distinct (conj fields field))})]
              (->> source
                   (map #(get % (clojure.core/name field)))
                   (map (fn [id-or-ids]
                          (cond->> (:_documents to-join)
                                   true (filter #((if (sequential? id-or-ids) (into #{} id-or-ids) #{id-or-ids}) (:_id %)))
                                   true (map (mongo->graphql _type))
                                   (not many?) first)))))))))))

(def connection-args
  (.getForwardPaginationConnectionFieldArguments relay))

(defn node-interface [type-resolver]
  (.nodeInterface relay type-resolver))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute [{:keys [graphql] :as deps} request ^String q & [^Map params]]
  (let [result (cond-> graphql
                       params (.execute q nil [deps request] params)
                       (not params) (.execute q [deps request]))]
    (if-let [data (.getData result)]
      (to-clj data)
      (do (prn "ERROR")
          (doseq [err (.getErrors result)]
            (log/error err))
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

;(deftype EmbeddedTypeReference [resource-key]
;  GraphQLTypeReference)

(defn type-ref [{:keys [resources-registry] :as deps} resource v]
  (cond
    (resource-embedded? v)
    (grape.graphql.GrapeTypeRef. (-> (get resources-registry (:resource-key v))
                                     :grape.graphql/type
                                     name)
                                 :embedded (:resource-key v) (:field v))
    (resource-join? v)
    (grape.graphql.GrapeTypeRef. (-> (get resources-registry (:resource-key v))
                                     :grape.graphql/type
                                     name)
                                 :join (:resource-key v) (:field v))))

(def type-ref? (partial instance? grape.graphql.GrapeTypeRef))

(defn resource->output-graphql-type [{:keys [resources-registry] :as deps} resource
                                     & {:keys [skip-hidden? skip-read-only?]
                                        :or   {skip-hidden? false skip-read-only? false}}]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        schema (:schema resource)
        type (-> resource :grape.graphql/type name)]
    (walk-schema
      schema
      (comp name #(if (= % :_id) :id %) schema.core/explicit-schema-key)
      (fn [path v]
        (cond
          (resource-embedded? v) (if (maybe? v)
                                   (type-ref deps resource (:schema v))
                                   (GraphQLNonNull. (type-ref deps resource v)))
          (resource-join? v) (type-ref deps resource v)
          (types-map v) (if (maybe? v)
                          (types-map (:schema v))
                          (GraphQLNonNull. (types-map v)))))
      :transform-map (fn [path m]
                       (object (if (= path []) type (name (gensym type)))
                               (->> m
                                    (filter (comp identity second))
                                    (map (fn [[k v]]
                                           (cond
                                             (and (instance? GraphQLList v) (instance? GraphQLNonNull (.getWrappedType v)) (type-ref? (.getWrappedType (.getWrappedType v))))
                                             (field k v :data-fetcher (type-ref-data-fetcher (.getWrappedType (.getWrappedType v)) :many? true))
                                             (and (instance? GraphQLList v) (type-ref? (.getWrappedType v)))
                                             (field k v :data-fetcher (type-ref-data-fetcher (.getWrappedType v) :many? true))
                                             (and (instance? GraphQLNonNull v) (type-ref? (.getWrappedType v)))
                                             (field k v :data-fetcher (type-ref-data-fetcher (.getWrappedType v)))
                                             (type-ref? v)
                                             (field k v :data-fetcher (type-ref-data-fetcher v))
                                             :else
                                             (field k v)))))))
      :transform-seq (fn [path v]
                       (when (seq (filter identity v))
                         (GraphQLList. (first v))))
      :skip-hidden? true
      :skip-read-only? false
      :skip-unwrap-for (fn [path v]
                         (or (and (maybe? v) (primitive? (:schema v)))
                             (resource-embedded? v)
                             (resource-join? v))))))

(defn resource->input-graphql-type [{:keys [resources-registry] :as deps} resource
                                    & {:keys [skip-hidden? skip-read-only?]
                                       :or   {skip-hidden? false skip-read-only? false}}]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        schema (:schema resource)
        type (-> resource :grape.graphql/type name)]
    (walk-schema
      schema
      (comp name #(if (= % :_id) :id %) schema.core/explicit-schema-key)
      (fn [path v]
        (cond
          (types-map v) (if (maybe? v)
                          (types-map (:schema v))
                          (GraphQLNonNull. (types-map v)))))
      :transform-map (fn [path m]
                       (input-object (if (= path []) type (name (gensym type)))
                                     (map (fn [[k v]] (input-field k v)) (filter (comp identity second) m))))
      :transform-seq (fn [path v]
                       (when (seq (filter identity v))
                         (GraphQLList. (first v))))
      :skip-hidden? false
      :skip-read-only? true
      :skip-unwrap-for (fn [path v]
                         (or (and (maybe? v) (primitive? (:schema v))))))))

(defn build-schema [{:keys [resources-registry] :as deps}]
  (let [types-map (->> (for [[resource-key resource] resources-registry]
                         [(name (->PascalCase resource-key)) (resource->output-graphql-type deps resource)])
                       (into {}))
        type-resolver (type-resolver (comp
                                       types-map
                                       #(get % "_type")))]
    (schema
      (object "QueryType"
              (->> (for [[resource-key resource] resources-registry
                         :let [type-name (name (->PascalCase resource-key))
                               type (get types-map type-name)
                               type-id-schema (.getType (.getFieldDefinition type "id"))]]
                     [(field (str type-name "List")
                             (connection (name resource-key) type type-resolver)
                             :arguments (concat connection-args
                                                [(argument "sort" Scalars/GraphQLString)
                                                 (argument "find" Scalars/GraphQLString)])
                             :data-fetcher (connection-data-fetcher
                                             (fn [deps request query ^DataFetchingEnvironment env]
                                               (let [resources (read-resource deps resource request query)]
                                                 (update resources :_documents
                                                         (partial map (mongo->graphql type-name)))))))
                      (field type-name
                             type
                             :arguments [(argument "id" type-id-schema)]
                             :data-fetcher (data-fetcher
                                             (fn [deps request ^DataFetchingEnvironment env]
                                               (let [query {:find {:_id (.getArgument env "id")}}
                                                     item (read-item deps resource request query)]
                                                 ((mongo->graphql type-name) item)))))])
                   flatten)
              :interfaces [(node-interface type-resolver)]))))
