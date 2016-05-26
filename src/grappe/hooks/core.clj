(ns grappe.hooks.core)

(def no-op-4
  (fn [deps resource request arg]
    arg))

(def no-op-5
  (fn [deps resource request payload existing]
    payload))

(defn gen-hook-fn-4 [hooks k]
  (fn [deps resource request query]
    (loop [[h & tail] hooks
           q query]
      (let [f (k h no-op-4)
            q (f deps resource request q)]
        (if (empty? tail)
          q
          (recur tail q))))))

(defn gen-hook-fn-5 [hooks k]
  (fn [deps resource request payload existing]
    (loop [[h & tail] hooks
           payload payload]
      (let [f (k h no-op-5)
            payload (f deps resource request payload existing)]
        (if (empty? tail)
          payload
          (recur tail payload))))))

(defn compose-hooks [& hooks]
  {:pre-fetch                (gen-hook-fn-4 hooks :pre-fetch)
   :pre-create-pre-validate  (gen-hook-fn-4 hooks :pre-create-pre-validate)
   :pre-create-post-validate (gen-hook-fn-4 hooks :pre-create-post-validate)
   :pre-update-pre-validate  (gen-hook-fn-5 hooks :pre-update-pre-validate)
   :pre-update-post-validate (gen-hook-fn-5 hooks :pre-update-post-validate)})
