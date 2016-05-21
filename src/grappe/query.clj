(ns grappe.query
  (:require [schema.core :as s]
            [schema.spec.core :as spec]
            [schema.spec.collection :refer [subschemas]]
            [grappe.utils :refer :all]
            [grappe.schema :refer :all]
            [grappe.hooks.core :refer :all]))

(def StrOrKeyword (s/either s/Str s/Keyword))
(def ^:const OPERATORS
  #{"$gt" "$gte" "$lt" "$lte" "$all" "$in" "$nin" "$ne" "$elemMatch" "$regex" "$options" "$and" "$or" "$nor"
    "$exists" "$mod" "$size" "$type" "$not" "$text" "$search" "$language" "$natural" "$ifNull" "$cond"
    "$geoWithin" "$geoIntersects" "$near"})
(def in-operators? OPERATORS)

(def Query
  {(? :find)      (s/maybe {StrOrKeyword s/Any})
   (? :fields)    (s/maybe [s/Keyword])
   (? :sort)      (s/maybe {s/Keyword {StrOrKeyword (s/enum 1 -1)}})
   (? :paginate)  (s/maybe {(? :page)     (s/maybe s/Int)
                            (? :per-page) (s/maybe s/Int)})
   (? :opts)      (s/maybe {(? :count?)    s/Bool
                            (? :paginate?) s/Bool
                            (? :sort?)     s/Bool})
   (? :relations) (s/maybe {s/Keyword (s/recursive #'Query)})})

(defn validate-mongo-find [resource where]
  (let [in-schema-keys?
        (->> (get-schema-keyseqs (:schema resource))
             flatten
             (map name)
             set)
        valid-key? (fn [k]
                     (or
                       (re-matches #"[0-9]+" k)
                       (in-schema-keys? k)
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
  [{:keys [hooks resources-registry] :as deps} :- s/Any resource :- s/Any request :- s/Any query :- Query]
  ;; The query is already structurally valid (validated by the Query Prismatic Schema)
  ;; Now we recursively walk the query spec, validates the where parameters and the query with hooks
  (letfn [(walk-query [res q]
            (validate-mongo-find res (:find q))
            (let [hook-fn (:pre-fetch (compose-hooks hooks resource))
                  q (hook-fn deps res request q)
                  {:keys [relations]} q]
              (merge (dissoc q :relations)
                     {:relations (apply merge (for [[relation-key relation-q] relations
                                                    :let [relation-spec (get-in res [:relations relation-key])
                                                          relation-res ((:resource relation-spec) resources-registry)
                                                          embedded? (= :embedded (:type relation-spec))
                                                          relation-q (if embedded?
                                                                       (merge relation-q {:opts {:count?    false
                                                                                                 :paginate? false
                                                                                                 :sort?     false}})
                                                                       (deep-merge relation-q {:opts {:count? false}}))]
                                                    :when (and relation-spec relation-res)]
                                                {relation-key (walk-query relation-res relation-q)}))})))]
    (walk-query resource query)))
