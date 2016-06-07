(ns grape.core
  (:refer-clojure :exclude [update count read])
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [grape.hooks.core :refer [compose-hooks]]
            [grape.schema :refer [validate-create validate-update validate-partial-update]]
            [grape.store :refer :all]
            [plumbing.core :refer :all]
            [clojure.core.match :refer [match]]
            [grape.query :refer [validate-query]]
            [cheshire.generate :refer [add-encoder encode-str remove-encoder]]
            [clj-time.format :as f]
            [com.rpl.specter :refer :all]
            [com.rpl.specter.macros :refer :all]
            [grape.utils :refer [get-schema-relations]]
            [com.climate.claypoole :as cp])
  (:import (org.joda.time DateTime)))

(add-encoder DateTime (fn [d jsonGenerator]
                        (let [formatter (f/formatters :date-time)
                              s (f/unparse formatter d)]
                          (.writeString jsonGenerator s))))

(def pool (cp/threadpool 10))
;; TODO swith this pool into the app-system to be able to access config and close it when the app stops

(declare read-resource)

(defn read-relation [deps resource request docs* rel-key rel-query]
  (let [rel-path (map (fn [part]
                        (if (= part "[]") ALL (keyword part)))
                      (clojure.string/split (name rel-key) #"\."))
        rel-spec (get (get-schema-relations (:schema resource)) rel-path)
        rel-resource (get-in deps [:resources-registry (:resource rel-spec)])
        docs @docs*]
    (match [(:type rel-spec) (boolean (or (:paginate rel-query) (:sort rel-query)))]
           [:embedded _]
           (let [;; First collect every id to fetch
                 rel-ids (distinct (mapcat #(select rel-path %) (map second docs)))
                 ;; build a map of ids to fetched documents
                 rel-docs-by-id (->> (:_documents
                                       (read-resource deps rel-resource request
                                                      (update-in rel-query [:find] #(merge % {:_id {"$in" rel-ids}}))))
                                     (map #(vector (:_id %) %))
                                     (into {}))]
             (doseq [[doc-id _] docs]
               (swap! docs* update-in [doc-id] #(transform rel-path rel-docs-by-id %))))
           [:join false]
           (let [arity-single? (= (:arity rel-spec) :single)
                 rel-ids (map first docs)
                 rel-field (:field rel-spec)
                 rel-docs-by-field (->> (:_documents
                                          (read-resource deps rel-resource request
                                                         (update-in rel-query [:find] #(merge % {rel-field {"$in" rel-ids}}))))
                                        (group-by rel-field))]
             (doseq [[doc-id _] docs]
               (swap! docs* update-in [doc-id] #(transform rel-path (constantly (-> (rel-docs-by-field doc-id)
                                                                                    (?> arity-single? first))) %))))
           [:join true]
           ;; no optimization possible, we do parallely, and given the threadpool, the fetching for each document
           (let [rel-ids (map first docs)
                 rel-field (:field rel-spec)
                 rel-docs-by-field (->> (cp/pmap pool
                                                 (fn [id]
                                                   [id (:_documents
                                                         (read-resource deps rel-resource request
                                                                        (update-in rel-query [:find] #(merge % {rel-field id}))))])
                                                 rel-ids)
                                        (into {}))]
             (doseq [[doc-id _] docs]
               (swap! docs* update-in [doc-id] #(transform rel-path (constantly (rel-docs-by-field doc-id)) %))))
           )))

(defn read-resource [{:keys [store hooks] :as deps} resource request query]
  (let [[pre-read-fn post-read-fn] ((juxt :pre-read :post-read) (compose-hooks hooks resource))
        query (pre-read-fn deps resource request query)
        query (validate-query deps resource request query {:recur? false})
        {:keys [relations]} query
        docs* (atom (->> (read store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)})
                         (map #(vector (:_id %) %))
                         (into {})))
        count? (get-in query [:opts :count?])
        count (when count?
                (count store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)}))]
    (when (seq relations)
      (cp/pdoseq pool
                 [[rel-key rel-query] relations]
                 (read-relation deps resource request docs* rel-key rel-query)))
    (->> {:_count     count
          :_query     query
          :_documents (map second @docs*)}
         (post-read-fn deps resource request))))

(defn read-item [deps resource request query]
  (->
    (read-resource deps resource request (assoc query :opts {:count? false :paginate? false :sort? false}))
    :_documents
    first
    (#(if (nil? %) (throw (ex-info "Not found" {:type :not-found})) %))))

(defn create-resource [{:keys [store hooks] :as deps} resource request payload]
  (let [hooks (compose-hooks hooks resource)
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

(defn update-resource [{:keys [store hooks] :as deps} resource request find payload]
  (let [hooks (compose-hooks hooks resource)
        [pre-validate-fn post-validate-fn post-update-fn post-update-async-fn]
        ((juxt :pre-update-pre-validate :pre-update-post-validate :post-update :post-update-async) hooks)
        existing (read-item deps resource request {:find find})]
    (let [updated (->> payload
                       (#(pre-validate-fn deps resource request % existing))
                       (validate-update deps resource request) ;; let the validation exception throw to the caller
                       (#(post-validate-fn deps resource request % existing))
                       (update store (get-in resource [:datasource :source]) (:_id payload))
                       (#(post-update-fn deps resource request % existing)))]
      (future (post-update-async-fn deps resource request updated existing))
      updated)))

(defn partial-update-resource [{:keys [store hooks] :as deps} resource request find payload]
  (let [hooks (compose-hooks hooks resource)
        [pre-validate-fn post-validate-fn post-update-fn post-update-async-fn]
        ((juxt :pre-partial-update-pre-validate :pre-partial-update-post-validate :post-partial-update :post-partial-update-async) hooks)
        existing (read-item deps resource request {:find find})]
    (let [updated (->> payload
                       (#(pre-validate-fn deps resource request % existing))
                       (validate-partial-update deps resource request) ;; let the validation exception throw to the caller
                       (#(post-validate-fn deps resource request % existing))
                       (partial-update store (get-in resource [:datasource :source]) (:_id payload))
                       (#(post-update-fn deps resource request % existing)))]
      (future (post-update-async-fn deps resource request updated existing))
      updated)))

(defn delete-resource [{:keys [store hooks] :as deps} resource request find]
  (let [hooks (compose-hooks hooks resource)
        [pre-delete-fn post-delete-fn post-delete-async-fn]
        ((juxt :pre-delete :post-delete :post-delete-async) hooks)
        existing (read-item deps resource request {:find find})]
    (->> (pre-delete-fn deps resource request existing)
         (delete store (get-in resource [:datasource :source]) (:_id find))
         (post-delete-fn deps resource request existing))
    (future (post-delete-async-fn deps resource request existing))
    existing))
