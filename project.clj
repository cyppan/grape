(defproject grappe "0.1.0-SNAPSHOT"
  :description "The opinionated, data-first, REST, GraphQL and Falcor enabled API Clojure library"
  :url "https://github.com/cyppan/grappe"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.6.1"]
                 [prismatic/schema "1.1.1"]
                 [prismatic/plumbing "0.5.3"]
                 [com.novemberain/monger "3.0.2"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [bidi "2.0.9"]]
  :plugins [[lein-cloverage "1.0.7-SNAPSHOT"]])
