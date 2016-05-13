(ns grappe.rest.parser
  (:require [cheshire.core :refer :all]))

(defn parse-query [request]
  (:query request))
