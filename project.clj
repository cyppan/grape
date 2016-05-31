(defproject grape "0.1.0-SNAPSHOT"
  :description "The opinionated, data-first, REST, GraphQL and Falcor enabled API Clojure library"
  :url "https://github.com/cyppan/grape"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.6.1"]
                 [clj-time "0.11.0"]
                 [prismatic/schema "1.1.1"]
                 [prismatic/plumbing "0.5.3"]
                 [metosin/schema-tools "0.9.0"]
                 [com.novemberain/monger "3.0.2"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [ring-middleware-accept "2.0.3"]
                 [bidi "2.0.9"]
                 [org.clojure/core.async "0.2.374"]
                 [slingshot "0.12.2"]
                 [com.taoensso/timbre "4.3.1"]
                 [org.slf4j/slf4j-nop "1.7.21"]]
  :plugins [[lein-cloverage "1.0.7-SNAPSHOT"]])
