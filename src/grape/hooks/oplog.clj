(ns grape.hooks.oplog
  (:refer-clojure :exclude [update count read])
  (:require [schema.core :as s]
            [grape.store :refer :all]
            [clj-time.core :as t])
  (:import (org.joda.time DateTime)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; # Oplog
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def OplogShema
  {:o    (s/enum :create :update :partial-update :delete)   ; operation performed
   :s    s/Str                                              ; datasource source
   :i    s/Str                                              ; document id
   :c    {s/Keyword s/Any}                                  ; document changes
   :auth (s/maybe {s/Keyword s/Any})                        ; auth from the request context
   :at   DateTime                                           ; related to the document
   })

(defn push-oplog [operation {:keys [store config]} resource request document & args]
  (insert store
          (get-in config [:oplog :source] "oplog")
          {:o    operation
           :s    (get-in resource [:datasource :source])
           :i    (:_id document)
           :c    (when-not (= operation :delete) document)
           :af   (when-let [doc-auth-field (get-in resource [:auth-strategy :doc-field])]
                   (get document doc-auth-field))
           :auth (:auth request) ;; Gives us more information about the  action, ex: who were impersonating the account
           :at   (t/now)}))

(def hooks
  {:post-create-async         (partial push-oplog :create)
   :post-update-async         (partial push-oplog :update)
   :post-partial-update-async (partial push-oplog :partial-update)
   :post-delete-async         (partial push-oplog :delete)})
