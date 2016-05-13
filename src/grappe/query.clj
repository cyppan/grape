(ns grappe.query
  (:require [schema.core :as s]))

(def ? s/optional-key)
(def StrOrKeyword (s/either s/Str s/Keyword))

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

(s/defn ^:always-validate validate-query
  [{:keys [hooks resources-registry] :as deps} :- s/Any resource :- s/Any request :- s/Any query :- Query]
  ;; The query is already structurally valid (validated by the Query Prismatic Schema)
  ;; Now we call recursively walk the query spec and map it using hooks
  ;; TODO use resource pre-fetch hook too
  (letfn [(walk-query [res q]
            (let [hook-fn (or (:pre-fetch hooks)
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
