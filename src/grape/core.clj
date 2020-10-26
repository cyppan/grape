(ns grape.core
  (:refer-clojure :exclude [update count read])
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [grape.hooks.utils :refer [compose-hooks]]
            [grape.schema :refer [validate-create validate-update validate-partial-update get-schema-relations]]
            [grape.store :refer :all]
            [plumbing.core :refer :all]
            [clojure.core.match :refer [match]]
            [grape.query :refer [validate-query]]
            [cheshire.generate :refer [add-encoder encode-str remove-encoder]]
            [clj-time.format :as f]
            [com.rpl.specter.macros :refer :all]
            [com.rpl.specter :refer [ALL select transform must]]
            [com.climate.claypoole :as cp]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [monger.collection :as mc])
  (:import (org.joda.time DateTime)
           (clojure.lang ExceptionInfo)))

(add-encoder DateTime (fn [d jsonGenerator]
                        (let [formatter (f/formatters :date-time)
                              s (f/unparse formatter d)]
                          (.writeString jsonGenerator s))))

(def pool (cp/threadpool (read-string (or (System/getenv "GRAPE_THREADPOOL_SIZE") "100"))))
;; TODO swith this pool into the app-system to be able to access config and close it when the app stops

(declare read-resource)

(defn read-relation [deps resource request docs* rel-key rel-query]
  (let [rel-path (map (fn [part]
                        (if (= part "[]") ALL (keyword part)))
                      (clojure.string/split (name rel-key) #"\."))
        rel-spec (get (get-schema-relations (:schema resource)) rel-path)
        rel-resource (get-in deps [:resources-registry (:resource rel-spec)])
        docs @docs*]
    (try
      (match [(:type rel-spec) (boolean (or (:paginate rel-query) (:sort rel-query)))]
             [:embedded _]
             (let [;; First collect every id to fetch
                   rel-ids (distinct (mapcat #(select rel-path %) (map second docs)))
                   ;; build a map of ids to fetched documents
                   rel-docs-by-id (->> (read-resource deps rel-resource request
                                                      (update-in rel-query [:find] #(merge % {:_id {"$in" rel-ids}})))
                                       :_documents
                                       (map #(vector (:_id %) %))
                                       (into {}))]
               (doseq [[doc-id _] docs
                       ; ensure not polluting docs with relations paths when nil
                       ; [:nested :key] would become [(must :nested) (must :key)]
                       :let [path (map #(if (keyword? %) (must %) %) rel-path)]]
                 (swap! docs* update-in [doc-id] #(transform path rel-docs-by-id %))))
             [:join false]
             (let [arity-single? (not= (last rel-path) ALL)
                   rel-ids (map first docs)
                   rel-field (:field rel-spec)
                   rel-docs-by-field (->> (read-resource deps rel-resource request
                                                         (update-in rel-query [:find]
                                                                    #(merge % {rel-field {"$in" rel-ids}})))
                                          :_documents
                                          (group-by rel-field))]
               (doseq [[doc-id _] docs]
                 (swap! docs* update-in [doc-id]
                        #(transform (drop-last rel-path) (constantly
                                                           (if (vector? (first (keys rel-docs-by-field))) ;; rel-field is an array
                                                             (->> rel-docs-by-field
                                                                  (filter (fn [[k _]]
                                                                            (first (filter (fn [el] (= el doc-id)) k))))
                                                                  first
                                                                  second)
                                                             (-> (rel-docs-by-field doc-id)
                                                                 (?> arity-single? first)))) %))))
             [:join true]
             ;; no optimization possible, we do parallely, and given the threadpool, the fetching for each document
             (let [rel-ids (map first docs)
                   rel-field (:field rel-spec)
                   rel-docs-by-field (->> (cp/pmap pool
                                                   (fn [id]
                                                     [id (:_documents
                                                           (read-resource deps rel-resource request
                                                                          (update-in rel-query [:find]
                                                                                     #(merge % {rel-field id}))))])
                                                   rel-ids)
                                          (into {}))]
               (doseq [[doc-id _] docs]
                 (swap! docs* update-in [doc-id]
                        #(transform (drop-last rel-path) (constantly (rel-docs-by-field doc-id)) %))))
             )
      (catch ExceptionInfo ex
        (if (#{:unauthorized :forbidden} (:type (ex-data ex)))
          (log/warn "relation fetching failed as " (:type (ex-data ex)))
          (throw ex))))))

(defn read-resource [{:keys [store hooks resources-registry] :as deps} resource request query]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        [pre-read-fn post-read-fn] ((juxt :pre-read :post-read) (compose-hooks hooks resource))
        query (pre-read-fn deps resource request query)
        query (validate-query deps resource request query {:recur? false})
        {:keys [relations find opts]} query
        {:keys [count? ids?]} opts
        find (if (:soft-delete resource)
               (if (:_deleted find) find (merge find {:_deleted {"$ne" true}}))
               find)
        ids (mc/find-maps (:db store) (get-in resource [:datasource :source]) find [:_id])
        count (when count?
                (future
                  (if ids?
                    (clojure.core/count ids)
                    (count store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)}))))
        items (read store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)})
        ordered-ids (map :_id items)
        docs* (atom (->> items
                         (map #(vector (:_id %) %))
                         (into {})))]
    (when (and (pos? (clojure.core/count @docs*)) (seq relations))
      (cp/pdoseq pool
                 [[rel-key rel-query] relations]
                 (read-relation deps resource request docs* rel-key rel-query)))
    (->> {:_count     (when count @count)
          :_ids       ids
          :_query     query
          :_documents (let [docs @docs*]
                        (into [] (map #(get docs %) ordered-ids)))}
         (post-read-fn deps resource request))))

(defn read-item [{:keys [resources-registry] :as deps} resource request query]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)]
    (-> (read-resource deps resource request (assoc query :opts {:count? false :paginate? false :sort? false}))
        :_documents
        first
        (#(if (nil? %) (throw (ex-info "Not found" {:type :not-found})) %)))))

(defn create-resource [{:keys [store hooks resources-registry] :as deps} resource request payload]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        hooks (compose-hooks hooks resource)
        [pre-validate-fn post-validate-fn post-create-fn post-create-async-fn]
        ((juxt :pre-create-pre-validate :pre-create-post-validate :post-create :post-create-async) hooks)]
    (let [created (->> payload
                       (pre-validate-fn deps resource request)
                       (validate-create deps resource request) ;; let the validation exception throw to the caller
                       (post-validate-fn deps resource request)
                       (insert store (get-in resource [:datasource :source]))
                       (post-create-fn deps resource request))]
      (future (post-create-async-fn deps resource request created))
      created)))

(defn update-resource [{:keys [store hooks resources-registry] :as deps} resource request find payload]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        hooks (compose-hooks hooks resource)
        [pre-validate-fn post-validate-fn post-update-fn post-update-async-fn]
        ((juxt :pre-update-pre-validate :pre-update-post-validate :post-update :post-update-async) hooks)
        existing (read-item deps resource (assoc request :grape/read-on-update true) {:find find})]
    (let [updated (->> payload
                       (#(pre-validate-fn deps resource request % existing))
                       (#(validate-update deps resource request % existing)) ;; let the validation exception throw to the caller
                       (#(post-validate-fn deps resource request % existing))
                       (update store (get-in resource [:datasource :source]) (:_id existing))
                       (#(post-update-fn deps resource request % existing)))]
      (future (post-update-async-fn deps resource request updated existing))
      updated)))

(defn- flatten-payload
  "This is a MongoDB related trick
  => flatten incoming structures in order to have a true partial update
  Ex: $set {'nested_doc.key' 1 'nested_doc.key2' 2}
  If the existing document in database contains a nil at a key in the embedded path,
  dont flatten cause Mongo will throw"
  [payload existing]
  (letfn [(f [m prefix]
            (cond
              (and (map? m) (not (and (contains? (get-in existing (drop-last prefix)) (last prefix))
                                      (= nil (get-in existing prefix)))))
              (mapcat (fn [[k v]]
                        (f v (concat prefix [k])))
                      m)
              :default
              [prefix m]))]
    (->> (f payload nil)
         (partition-all 2)
         (map (fn [[k v]] [(keyword (str/join "." (map #(if (keyword? %) (name %) %) k))) v]))
         (into {}))))

(defn partial-update-resource [{:keys [store hooks resources-registry] :as deps} resource request find payload]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        hooks (compose-hooks hooks resource)
        [pre-validate-fn post-validate-fn post-update-fn post-update-async-fn]
        ((juxt :pre-partial-update-pre-validate :pre-partial-update-post-validate :post-partial-update :post-partial-update-async) hooks)
        existing (read-item deps resource (assoc request :grape/read-on-update true) {:find find})
        hooked-payload (as-> payload p
                             (pre-validate-fn deps resource request p existing)
                             (validate-partial-update deps resource request p existing) ;; let the validation exception throw to the caller
                             (post-validate-fn deps resource request p existing))
        flat-hooked-payload (flatten-payload hooked-payload existing)]
    (partial-update store (get-in resource [:datasource :source]) (:_id existing) flat-hooked-payload)
    (let [updated (post-update-fn deps resource request hooked-payload existing)]
      (future (post-update-async-fn deps resource request updated existing))
      updated)))

(defn delete-resource [{:keys [store hooks resources-registry] :as deps} resource request find]
  (let [resource (if (keyword? resource) (get resources-registry resource) resource)
        hooks (compose-hooks hooks resource)
        [pre-delete-fn post-delete-fn post-delete-async-fn]
        ((juxt :pre-delete :post-delete :post-delete-async) hooks)
        existing (read-item deps resource request {:find find})]
    (pre-delete-fn deps resource request existing)
    (delete store (get-in resource [:datasource :source]) (:_id existing) {:soft-delete? (:soft-delete resource)})
    (post-delete-fn deps resource request existing)
    (future (post-delete-async-fn deps resource request existing))
    existing))
