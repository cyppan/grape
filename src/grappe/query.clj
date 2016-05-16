(ns grappe.query
  (:require [schema.core :as s]))

(def ? s/optional-key)
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

(defn deep-merge
  "Recursively merges maps. If keys are not maps, the last value wins."
  [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

;; TODO USE SUBSCHEMAS!!!
;; https://github.com/plumatic/schema/wiki/Defining-New-Schema-Types-1.0#and-more
(defn path-vals
  "Returns vector of tuples containing path vector to the value and the value."
  ([m] (path-vals m identity))
  ([m fk]
   (letfn
     [(pvals [l p m]
        (reduce
          (fn [l [k v]]
            (let [k (fk k)]
              (cond
                (map? v) (pvals l (conj p k) v)
                (vector? v) (concat l (mapcat identity (for [el v :when (or (map? el) (vector? el))] (pvals '() (conj p k []) el))))
                :else (cons [(conj p k) v] l))))
          l m))]
     (pvals [] [] m))))

(defn validate-mongo-find [resource where]
  (let [in-schema-keys?
        (->> (path-vals (:schema resource))
             (map first)
             (map (fn [m] (map (fn [v] (if (vector? v) v (s/explicit-schema-key v))) m)))
             (map (fn [m] (filter #(not (#{:schema :schemas :p? :pred-name :vs} %)) m)))
             flatten
             (map name)
             set)
        where-keys
        (->> (path-vals where)
             (map first)
             (map (fn [m] (mapcat #(clojure.string/split (name %) #"\.") m)))
             flatten)
        invalid-keys
        (filter (fn [k] (not (or (in-schema-keys? k) (in-operators? k) (= "_deleted" k)))) where-keys)]
    (if (> (count invalid-keys) 0)
      (throw (ex-info "find spec invalid" {:status 422 :body {:errors {:query {:find (clojure.string/join invalid-keys ",")}}}}))
      where)))

(s/defn ^:always-validate validate-query
  [{:keys [hooks resources-registry] :as deps} :- s/Any resource :- s/Any request :- s/Any query :- Query]
  ;; The query is already structurally valid (validated by the Query Prismatic Schema)
  ;; Now we recursively walk the query spec, validates the where parameters and the query with hooks
  ;; TODO use resource pre-fetch hook too
  (letfn [(walk-query [res q]
            (let [_ (validate-mongo-find res (:find q))
                  hook-fn (or (:pre-fetch hooks)
                              (fn [deps resource request query] query))
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
