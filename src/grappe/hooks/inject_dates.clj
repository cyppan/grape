(ns grappe.hooks.inject-dates)

(def hooks
  {:pre-create (fn [deps resource request payload] payload)})
