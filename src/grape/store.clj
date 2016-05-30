(ns grape.store
  (:refer-clojure :exclude [update count])
  (:require [plumbing.core :refer :all]
            [monger.collection :as mc]
            [monger.query :as mq]
            monger.joda-time
            monger.json)
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
