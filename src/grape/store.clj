(ns grape.store
  (:refer-clojure :exclude [update count])
  (:require [plumbing.core :refer :all]
            [monger.collection :as mc]
            [monger.query :as mq]
            [monger.util :refer :all]
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
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}}))
                 find)
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
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}}))
                 find)]
      (mc/count db source find)))
  (insert [_ source document]
    (mc/insert-and-return db source document))
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
