(ns grape.hooks.core
  (:require [grape.hooks.auth-field :refer [hooks] :rename {hooks auth-field-hooks}]
            [grape.hooks.default-sort :refer [hooks] :rename {hooks default-sort-hooks}]
            [grape.hooks.inject-dates :refer [hooks] :rename {hooks inject-dates-hooks}]
            [grape.hooks.restricts-fields :refer [hooks] :rename {hooks restrict-fields-hooks}]
            [grape.hooks.inject-pagination :refer [hooks] :rename {hooks inject-pagination-hooks}]
            [grape.hooks.oplog :refer [hooks] :rename {hooks oplog-hooks}]
            [grape.hooks.utils :refer [compose-hooks]]))

(def hooks (compose-hooks auth-field-hooks
                          default-sort-hooks
                          inject-dates-hooks
                          oplog-hooks
                          restrict-fields-hooks
                          inject-pagination-hooks))
