(ns grape.store
  (:refer-clojure :exclude [update count read])
  (:require [plumbing.core :refer :all]
            [monger.collection :as mc]
            [monger.query :as mq]
            [monger.util :refer :all]
            monger.joda-time
            monger.json
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [monger.core :as mg]
            [schema.core :as s]
            [clj-time.format :as f])
  (:import (org.bson.types ObjectId)))

(defprotocol DataSource
  (read [_ source query opts])
  (count [_ source query opts])
  (insert [_ source document])
  (partial-update [_ source id document])
  (update [_ source id document])
  (delete [_ source id opts]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; # Types definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def MongoDbConfig {:uri s/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; # MongoDB DataSource Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find->mongo-query
  "This is a naive mapping from the Grape query DSL to Mongodb query,
  TODO: use the MongoDB Extended JSON format"
  [find]
  (clojure.walk/prewalk
    #(cond
       (and (string? %) (ObjectId/isValid %))
       (ObjectId. %)
       (and (string? %) (re-matches #"[1-2][0-9]{3}-[0-1][0-9]-[0-3][0-9]T[0-2][0-9]:[0-5][0-9]:[0-5][0-9]\.[0-9]{3}Z" %))
       (f/parse (f/formatters :date-time) %)
       :default
       %)
    find))

(defrecord MongoDataSource [;; construction parameters
                            uri
                            ;; constructed on start
                            conn db]
  DataSource
  (read [_ source {:keys [find fields paginate sort] :as query} {:keys [soft-delete?] :as opts}]
    (let [find (if soft-delete?
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}}))
                 find)
          {:keys [paginate? sort?] :or {paginate? true sort? true} :as opts} (:opts query)
          find (find->mongo-query find)
          skip-limit? (and paginate? (:limit paginate))]
      (mq/with-collection
        db source
        (mq/find find)
        (merge (if fields (mq/partial-query (mq/fields fields)) {}))
        (merge (if (and sort? sort) (mq/partial-query (mq/sort sort)) {}))
        (merge (if (and paginate? (not skip-limit?))
                 (mq/partial-query (mq/paginate :page (:page (or paginate {}) 1) :per-page (:per-page (or paginate {}) 50)))
                 {}))
        (merge (if (and paginate? (:skip paginate))
                 (mq/partial-query (mq/skip (:skip paginate)))))
        (merge (if (and paginate? (:limit paginate))
                 (mq/partial-query (mq/limit (:limit paginate))))))))
  (count [_ source {:keys [find]} {:keys [soft-delete?] :as opts}]
    (let [find (if soft-delete?
                 (if (:_deleted find) find (merge find {:_deleted {"$ne" true}}))
                 find)
          find (find->mongo-query find)]
      (mc/count db source find)))
  (insert [_ source document]
    (mc/insert-and-return db source document))
  (partial-update [self source id document]
    (let [coerced (if (and (string? id) (re-matches #"[a-z0-9]{24}" id)) (ObjectId. id) id)]
      (mc/update db source {:_id coerced} {"$set" (dissoc document :_id)})
      document))
  (update [self source id document]
    (let [coerced (if (and (string? id) (re-matches #"[a-z0-9]{24}" id)) (ObjectId. id) id)]
      (mc/update db source {:_id coerced} (dissoc document :_id))
      document))
  (delete [self source id {:keys [soft-delete?] :as opts}]
    (let [coerced (if (and (string? id) (re-matches #"[a-z0-9]{24}" id)) (ObjectId. id) id)]
      (if soft-delete?
        (partial-update self source id {:_deleted true})
        (mc/remove db source {:_id coerced}))))

  component/Lifecycle
  (start [this]
    (log/info "starting MongoDB database")
    (if (:conn this)
      this
      (let [{:keys [conn db]} (mg/connect-via-uri uri)]
        (assoc this :conn conn :db db))))

  (stop [this]
    (log/info "stopping MongoDB database")
    (if (:conn this)
      (do
        (mg/disconnect (:conn this))
        (assoc this :conn nil :db nil))
      this)))

(s/defn ^:always-validate new-mongo-datasource [config :- MongoDbConfig]
  (map->MongoDataSource config))
