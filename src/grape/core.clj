(ns grape.core
  (:refer-clojure :exclude [update count read])
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [grape.hooks.core :refer [compose-hooks]]
            [grape.schema :refer [validate-create validate-update validate-partial-update]]
            [grape.store :refer :all]
            [plumbing.core :refer :all]
            [clojure.core.match :refer [match]]
            [plumbing.graph :as graph]
            [grape.query :refer [validate-query]]
            [cheshire.generate :refer [add-encoder encode-str remove-encoder]]
            [clj-time.format :as f])
  (:import (org.joda.time DateTime)))

(add-encoder DateTime (fn [d jsonGenerator]
                        (let [formatter (f/formatters :date-time)
                              s (f/unparse formatter d)]
                          (.writeString jsonGenerator s))))

(declare read-resource)

(defn read-relation-resource [deps resource request relation-key relation-query documents]
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
             (-> (read-resource deps rel-resource request rel-query)
                 :_documents))
           [:ref-field true]
           ;; Here no optimization possible, we do one request by document (in parallel at least)
           (->> documents
                (map :_id)
                (pmap (fn [id]
                        (let [field (:field relation-spec)
                              rel-query (update-in relation-query [:find] #(merge % {field id}))]
                          [id (read-resource deps rel-resource request rel-query)])))
                (into {}))
           [:embedded _]
           (let [ids (flatten (map #(get-in % (:path relation-spec)) documents))
                 rel-query (update-in relation-query [:find] #(merge % {:_id {"$in" ids}}))]
             (-> (read-resource deps rel-resource request rel-query)
                 :_documents))
           [_ _] nil)))

(defn read-resource [{:keys [store hooks] :as deps} resource request query]
  (let [[pre-read-fn post-read-fn] ((juxt :pre-read :post-read) (compose-hooks hooks resource))
        query (pre-read-fn deps resource request query)
        query (validate-query deps resource request query {:recur? false})
        {:keys [relations]} query
        fetch-graph
        {:documents
         (fnk []
           (read store (get-in resource [:datasource :source]) query {:soft-delete? (:soft-delete resource)}))}
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
                        (read-relation-resource deps resource request relation-key relation-query documents))}))))
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
               {:_documents (into [] %)}))
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
        ((juxt :pre-update-pre-validate :pre-update-post-validate :post-update :post-update-async) hooks)
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
