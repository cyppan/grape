(ns grape.query
  (:require [schema.core :as s]
            [schema.spec.core :as spec]
            [schema.spec.collection :refer [subschemas]]
            [grape.utils :refer :all]
            [grape.schema :refer [get-schema-keyseqs get-schema-relations]]
            [grape.hooks.core :refer :all]
            [com.rpl.specter :refer [ALL]]))

(def ? s/optional-key)
(def StrOrKeyword (s/either s/Str s/Keyword))
(def ^:const OPERATORS
  #{"$gt" "$gte" "$lt" "$lte" "$all" "$in" "$nin" "$ne" "$elemMatch" "$regex" "$options" "$and" "$or" "$nor"
    "$exists" "$mod" "$size" "$type" "$not" "$text" "$search" "$language" "$natural" "$ifNull" "$cond"
    "$geoWithin" "$geoIntersects" "$near"})
(def in-operators? OPERATORS)

(def Query
  {(? :find)      (s/maybe {StrOrKeyword s/Any})
   (? :fields)    (s/maybe [StrOrKeyword])
   (? :sort)      (s/maybe {StrOrKeyword (s/enum 1 -1)})
   (? :paginate)  (s/maybe {(? :page)     (s/maybe s/Int)
                            (? :per-page) (s/maybe s/Int)
                            (? :skip)     (s/maybe s/Int)
                            (? :limit)    (s/maybe s/Int)})
   (? :opts)      (s/maybe {(? :count?)    s/Bool
                            (? :paginate?) s/Bool
                            (? :sort?)     s/Bool})
   (? :relations) (s/maybe {s/Keyword (s/recursive #'Query)})})

(defn validate-query-find [resource where]
  (let [in-schema-keys?
        (->> (get-schema-keyseqs (:schema resource))
             (into [])
             flatten
             (map name)
             set)
        in-fields?
        (into #{} (map name (:fields resource #{})))
        valid-key? (fn [k]
                     (or
                       (re-matches #"[0-9]+" k)
                       (in-schema-keys? k)
                       (in-fields? k)
                       (in-operators? k)
                       (= "_deleted" k)))]
    (-> where
        (walk-structure (fn [form]
                          (->> (clojure.string/split (name form) #"\.")
                               (filter #(not (= [] %))))) identity)
        (walk-structure (fn [form]
                          (doseq [k form
                                  :when (not (valid-key? k))]
                            (throw (ex-info "find invalid, should only contains query DSL elements or existing resource schema keys"
                                            {:error-key "invalid-find"
                                             :status    422
                                             :errors    {:query {:find k}}})))
                          form) identity))))

(s/defn ^:always-validate validate-query
  [{:keys [hooks resources-registry] :as deps} :- s/Any
   resource :- s/Any
   request :- s/Any
   query :- Query
   {:keys [recur?]}]
  ;; The query is already structurally valid (validated by the Query Prismatic Schema)
  ;; Now we walk the query spec, validates the where parameters and the query with hooks
  (validate-query-find resource (:find query))
  (let [{:keys [relations]} query]
    (assoc query
      :relations (apply merge (for [[relation-key relation-q] relations
                                    :let [relation-path (map (fn [part]
                                                               (if (= part "[]")
                                                                 ALL
                                                                 (keyword part))) (clojure.string/split (name relation-key) #"\."))
                                          [relation-path relation-spec] (->> (get-schema-relations (:schema resource))
                                                                             (filter (fn [[path _]]
                                                                                       (or (= (seq relation-path) (seq path))
                                                                                           (= (filter (partial not= ALL) relation-path)
                                                                                              (filter (partial not= ALL) path)))))
                                                                             first)
                                          relation-key (keyword (clojure.string/join "." (map #(if (= ALL %) "[]" (name %)) relation-path)))
                                          relation-res (when relation-spec (get resources-registry (:resource relation-spec)))
                                          embedded? (when relation-spec (= :embedded (:type relation-spec)))
                                          relation-q (if embedded?
                                                       (deep-merge relation-q {:opts {:count?    false
                                                                                      :paginate? false
                                                                                      :sort?     false}})
                                                       (deep-merge relation-q {:opts {:count? false}}))]
                                    :when (and relation-spec relation-res)]
                                {relation-key (if recur?
                                                (validate-query deps relation-res request relation-q {:recur? true})
                                                relation-q)})))))
