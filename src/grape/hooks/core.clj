(ns grape.hooks.core)

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
  {:pre-read                 (gen-hook-fn-4 hooks :pre-read)
   :post-read                (gen-hook-fn-4 hooks :post-read)
   :pre-create-pre-validate  (gen-hook-fn-4 hooks :pre-create-pre-validate)
   :pre-create-post-validate (gen-hook-fn-4 hooks :pre-create-post-validate)
   :post-create              (gen-hook-fn-4 hooks :post-create)
   :post-create-async        (gen-hook-fn-4 hooks :post-create-async)
   :pre-update-pre-validate  (gen-hook-fn-5 hooks :pre-update-pre-validate)
   :pre-update-post-validate (gen-hook-fn-5 hooks :pre-update-post-validate)
   :post-update              (gen-hook-fn-5 hooks :post-update)
   :post-update-async        (gen-hook-fn-5 hooks :post-update-async)
   :pre-delete               (gen-hook-fn-4 hooks :pre-delete)
   :post-delete              (gen-hook-fn-4 hooks :post-delete)
   :post-delete-async        (gen-hook-fn-4 hooks :post-delete-async)})
