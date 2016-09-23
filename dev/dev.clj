(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require
    [clojure.java.io :as io]
    [clojure.java.javadoc :refer [javadoc]]
    [clojure.pprint :refer [pprint]]
    [clojure.reflect :refer [reflect]]
    [clojure.repl :refer [apropos dir doc find-doc pst source]]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :as test]
    [clojure.tools.namespace.repl :refer [refresh refresh-all]]
    [schema.core :as s]
    [grape.fixtures.comments :refer :all]
    [grape.schema :as gs]
    [monger.core :as mg]
    [cheshire.core :refer [generate-string parse-string]]
    [grape.store :as store]
    [grape.hooks.core :refer [hooks]]
    [grape.graphql.core :refer :all])
  (:import (clojure.lang ExceptionInfo)
           (org.bson.types ObjectId)))

(def mongo-uri "mongodb://localhost:27017/test")
(def mongo-conn nil)

(def system
  "A Var containing an object representing the application under
  development."
  nil)

(defn start
  "Starts the system running, updates the Var #'system."
  []
  (let [{conn :conn db :db} (mg/connect-via-uri mongo-uri)
        deps (assoc deps
               :store (store/map->MongoDataSource {:db db}))]
    (alter-var-root #'mongo-conn (constantly conn))
    (alter-var-root #'system (constantly deps))))

(defn stop
  "Stops the system if it is currently running, updates the Var
  #'system."
  []
  (alter-var-root #'mongo-conn (fn [v] mg/disconnect))
  (alter-var-root #'system (fn [v] (constantly {}))))

(defn go
  "Initializes and starts the system running."
  []
  (start)
  :ready)

(defn reset
  "Stops the system, reloads modified source files, and restarts it."
  []
  (stop)
  (refresh :after `go))