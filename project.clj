(defproject beop-grape "0.2.0-SNAPSHOT"
  :description "The opinionated, data-first, REST API Clojure library"
  :url "https://github.com/BeOpinion/grape"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [cheshire "5.10.0"]
                 [clj-time "0.15.2"]
                 [prismatic/schema "1.1.12"]
                 [prismatic/plumbing "0.5.5"]
                 [metosin/schema-tools "0.12.3"]
                 [com.novemberain/monger "3.5.0"]
                 [org.clojure/core.match "1.0.0"]
                 [ring-middleware-accept "2.0.3"]
                 [bidi "2.1.6"]
                 [org.clojure/core.async "1.3.610"]
                 [slingshot "0.12.2"]
                 [com.taoensso/timbre "5.1.2"]
                 [com.fzakaria/slf4j-timbre "0.3.21"]
                 [org.slf4j/log4j-over-slf4j "1.7.30"]
                 [org.slf4j/jul-to-slf4j "1.7.30"]
                 [org.slf4j/jcl-over-slf4j "1.7.30"]
                 [com.stuartsierra/component "0.4.0"]
                 [http-kit "2.5.3"]
                 [com.rpl/specter "1.1.3"]
                 [com.climate/claypoole "1.1.4"]
                 [ring/ring-core "1.9.1"]
                 [jumblerg/ring.middleware.cors "1.0.1"]
                 [ring/ring-json "0.5.1"]
                 [clj-http "3.12.1"]
                 [buddy/buddy-sign "3.3.0"]]
  :plugins [[lein-cloverage "1.2.2"]]
  :profiles {:repl       {:main dev}
             :dev        {:dependencies [[org.clojure/tools.namespace "1.1.0"]
                                         [org.slf4j/slf4j-nop "1.7.30"]
                                         ]
                          :source-paths ["dev"]}
             :beop-grape {:name         "beop-grape"
                          :group        "beop-grape"
                          :jar-name     "beop-grape.jar"
                          :plugins      [[s3-wagon-private "1.3.4"]]
                          :repositories [["private" {:url           "s3p://beopinion-clojars/releases/"
                                                     :sign-releases false
                                                     :no-auth       true}]]}})
