(ns grappe.store
  (:refer-clojure :exclude [update count])
  (:require [plumbing.core :refer :all]
            [clojure.core.match :refer [match]]
            [plumbing.graph :as graph]
            [monger.collection :as mc]
            [monger.query :as mq]
            [grappe.query :refer [validate-query]])
  (:import (org.bson.types ObjectId)))

(defprotocol DataSource
  (fetch [_ source query opts])
  (count [_ source query opts])
  (insert [_ source document])
  (partial-update [_ source id document])
  (update [_ source id document])
  (delete [_ source id opts]))

(defrecord MongoDataSource [db]
  DataSource
  (fetch [_ source {:keys [find fields paginate sort] :as query} {:keys [soft-delete?] :as opts}]
    (let [find (if soft-delete?
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}})))
          {:keys [paginate? sort?] :or {paginate? true sort? true} :as opts} (:opts query)
          find (clojure.walk/prewalk #(if (and (string? %) (re-matches #"[a-z0-9]{24}" %)) (ObjectId. %) %) find)]
      (mq/with-collection
        db source
        (mq/find find)
        (merge (if fields (mq/partial-query (mq/fields fields)) {}))
        (merge (if (and sort? sort) (mq/partial-query (mq/sort sort)) {}))
        (merge (if paginate?
                 (mq/partial-query (mq/paginate :page (:page (or paginate {}) 1) :per-page (:per-page (or paginate {}) 50)))
                 {})))))
  (count [_ source {:keys [find]} {:keys [soft-delete?] :as opts}]
    (let [find (if soft-delete?
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}})))]
      (mc/count db source find)))
  (insert [_ source document]
    (mc/insert db source document))
  (partial-update [_ source id document]
    (let [coerced (if (and (string? id) (re-matches #"[a-z0-9]{24}" id)) (ObjectId. id) id)]
      (mc/update db source {:_id coerced} {"$set" document})))
  (update [_ source id document]
    (let [coerced (if (and (string? id) (re-matches #"[a-z0-9]{24}" id)) (ObjectId. id) id)]
      (mc/update db source {:_id coerced} document)))
  (delete [self source id {:keys [soft-delete?] :as opts}]
    (let [coerced (if (and (string? id) (re-matches #"[a-z0-9]{24}" id)) (ObjectId. id) id)]
      (if soft-delete?
        (partial-update self source id {:_deleted true})
        (mc/remove db source {:_id coerced})))))

(defrecord AtomDataSource [atom-map]
  DataSource
  (fetch [_ source {:keys [find paginate sort fields] :as query} {:keys [soft-delete?] :as opts}]
    (let [find (if soft-delete?
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}})))
          {:keys [paginate? sort?] :or {paginate? true sort? true} :as opts} (:opts query)
          documents (get @atom-map source)
          coerced (clojure.walk/prewalk #(if (and (string? %) (re-matches #"[a-z0-9]{24}" %)) (ObjectId. %) %) find)]
      (->> documents
           (filter
             (fn [doc]
               (every? (fn [[path value]]
                         (cond
                           (and (map? value) (= "$in" (first (keys value))))
                           ((set (get value "$in")) (get-in doc [path]))
                           (and (map? value) (= "$ne" (first (keys value))))
                           (not= (get value "$ne") (get-in doc [path]))
                           :else
                           (= value (get-in doc [path]))))
                       coerced)))
           (?>> (and paginate? (:per-page paginate)) (drop (* (dec (or (:page paginate 1))) (:per-page paginate))))
           (?>> (and paginate? (:per-page paginate)) (take (:per-page paginate)))
           (map #(select-keys % fields)))))
  (count [self source query opts]
    (clojure.core/count (fetch self source (assoc query :opts {:paginate? false :sort? false}) opts)))
  (insert [_ source document]
    (swap! atom-map
           (fn [m]
             (update-in m [source] (fn [v]
                                   (conj (or v []) document))))))
  (partial-update [_ source id document]
    (swap! atom-map
           (fn [m]
             (update-in m [source] (fn [v]
                                   (map (fn [doc]
                                          (if (= (:_id doc) id)
                                            (merge doc document)
                                            doc))
                                        v))))))
  (update [_ source id document]
    (swap! atom-map
           (fn [m]
             (update-in m [source] (fn [v]
                                   (map (fn [doc]
                                          (if (= (:_id doc) id)
                                            document
                                            doc))
                                        v))))))
  (delete [self source id {:keys [soft-delete?] :as opts}]
    (swap! atom-map
           (fn [m]
             (if soft-delete?
               (partial-update self source id {:_deleted true})
               (update-in m [source] (fn [v]
                                     (filter
                                       (fn [doc]
                                         (not= (:_id doc) id))
                                       v))))))))

;(def store
;  (->AtomDataSource
;    (atom {"questions" [{:_id "q1" :account "a1" :text "toto"}
;                        {:_id "q2" :account "a1" :text "tata"}
;                        {:_id "q3" :account "a2" :text "titi"}]})))

(declare fetch-resource)

(defn fetch-relation-resource [deps resource request relation-key relation-query documents]
  (let [{resources-registry :resources-registry} deps
        relation-spec (get-in resource [:relations relation-key])
        rel-resource (resources-registry (:resource relation-spec))
        rel-paginate (or (:paginate relation-query) (get-in relation-spec [:query :paginate]))
        rel-sort (or (:sort relation-query) (get-in relation-spec [:query :sort]))]
    (match [(:type relation-spec) (boolean (or rel-paginate rel-sort))]
      [:ref-field false]
      ;; if no paginate or sort, we can batch the fetch of relations for all the documents
      ;; and group by the relation field later when associating the relations
      (let [field (:field relation-spec)
            rel-query (update-in relation-query [:find] #(merge % {field {"$in" (map :_id documents)}}))]
        (-> (fetch-resource deps rel-resource request rel-query)
            :_documents))
      [:ref-field true]
      ;; Here no optimization possible, we do one request by document (in parallel at least)
      (->> documents
           (map :_id)
           (pmap (fn [id]
                   (let [field (:field relation-spec)
                         rel-query (update-in relation-query [:find] #(merge % {field id}))]
                     [id (fetch-resource deps rel-resource request rel-query)])))
           (into {}))
      [:embedded _]
      (let [ids (flatten (map #(get-in % (:path relation-spec)) documents))
            rel-query (update-in relation-query [:find] #(merge % {:_id {"$in" ids}}))]
        (-> (fetch-resource deps rel-resource request rel-query)
            :_documents))
      [_ _] nil)))

(defn fetch-resource [{:keys [store hooks] :as deps} resource request query]
  (let [{:keys [relations]} query
        fetch-graph
        {:documents
         (fnk []
           (fetch store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)}))}
        count? (get-in query [:opts :count?])
        fetch-graph-with-count
        (if count?
          (assoc fetch-graph :count (fnk [] (count store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)})))
          fetch-graph)
        fetch-graph-with-relations
        (if (empty? relations)
          fetch-graph-with-count
          (merge
            fetch-graph-with-count
            (apply merge
                   (for [[relation-key relation-query] relations]
                     {relation-key
                      (fnk [documents]
                        (fetch-relation-resource deps resource request relation-key relation-query documents))}))))
        fetcher (graph/par-compile fetch-graph-with-relations)
        fetched (fetcher {})]
    (->>
      (for [doc (:documents fetched)]
        (merge doc
               (apply merge
                      (for [[relation-key _] relations
                            :let [relation-spec (get-in resource [:relations relation-key])
                                  relations-fetched (relation-key fetched)]]
                        (condp = (:type relation-spec)
                          :embedded
                          (let [path (:path relation-spec)
                                at-path (get-in doc path)]
                            (if (vector? at-path)
                              (assoc-in {} path (into [] (filter #((set at-path) (:_id %)) relations-fetched)))
                              (assoc-in {} path (first (filter #(= (:_id %) at-path) relations-fetched)))))
                          :ref-field
                          (let [path (:path relation-spec)
                                field (:field relation-spec)
                                arity-single? (= :single (:arity relation-spec))]
                            (if (map? relations-fetched)
                              (assoc-in {} path (get relations-fetched (:_id doc)))
                              (assoc-in {} path
                                        (->> relations-fetched
                                             (filter #(= (field %) (:_id doc)))
                                             (into [])
                                             (?>> arity-single? first))))))))))
      (#(merge {:_count (:count fetched)}
               {:_query query}
               {:_documents (into [] %)})))))

(defn fetch-item [deps resource request query]
  (->
    (fetch-resource deps resource request (assoc query :opts {:count? false :paginate? false :sort? false}))
    :_documents
    first
    (#(if (nil? %) (throw (ex-info "Not found" {:status 404})) %))))