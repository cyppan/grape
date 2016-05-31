(ns grape.hooks.inject-dates
  (:require [clj-time.core :as t]))

(def hooks
  {:pre-create-post-validate
   (fn [deps resource request payload]
     (merge (or payload {}) {:_created (t/now)}))})
