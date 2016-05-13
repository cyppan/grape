(ns grappe.hooks.core)

(def pre-fetch-no-op
  (fn [deps resource request query]
    query))

(defn compose-hooks [& hooks]
  {:pre-fetch
   (fn [deps resource request query]
     (loop [[h & tail] hooks
            q query]
       (let [f (:pre-fetch h pre-fetch-no-op)
             q (f deps resource request q)]
         (if (empty? tail)
           q
           (recur tail q)))))})
