(defproject beop-grape "0.1.12-SNAPSHOT"
  :description "The opinionated, data-first, REST, GraphQL and Falcor enabled API Clojure library"
  :url "https://github.com/cyppan/grape"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.7.0"]
                 [clj-time "0.13.0"]
                 [prismatic/schema "1.1.3"]
                 [prismatic/plumbing "0.5.3"]
                 [metosin/schema-tools "0.9.0"]
                 [com.novemberain/monger "3.1.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [ring-middleware-accept "2.0.3"]
                 [bidi "2.0.16"]
                 [org.clojure/core.async "0.2.395"]
                 [slingshot "0.12.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-api "1.7.23"]
                 [org.slf4j/jul-to-slf4j "1.7.23"]
                 [org.slf4j/log4j-over-slf4j "1.7.23"]
                 [org.slf4j/jcl-over-slf4j "1.7.23"]
                 [com.stuartsierra/component "0.3.2"]
                 [http-kit "2.2.0"]
                 [com.rpl/specter "1.1.0"]
                 [com.climate/claypoole "1.1.4"]
                 [ring/ring-core "1.5.1"]
                 [jumblerg/ring.middleware.cors "1.0.1"]
                 [ring/ring-json "0.4.0"]
                 [clj-http "3.4.1"]
                 [com.graphql-java/graphql-java "2.3.0"]
                 [buddy/buddy-sign "1.4.0"]]
  :aot [grape.graphql.GrapeTyperef]
  :plugins [[rfkm/lein-cloverage "1.0.8"]
            [s3-wagon-private "1.3.0"]]
  :profiles {:repl       {:main dev}
             :dev        {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                         [org.slf4j/slf4j-simple "1.7.23"]]
                          :source-paths ["dev"]}
             :uberjar    {:aot [grape.graphql.GrapeTyperef]}
             :beop-grape {:name         "beop-grape"
                          :group        "beop-grape"
                          ; :java-source-paths ^:replace ["content-commons/src/java"]
                          :jar-name     "beop-grape.jar"
                          :plugins      [[s3-wagon-private "1.3.0"]]
                          :repositories [["private" {:url     "s3p://beopinion-clojars/releases/"
                                                     :no-auth true}]]}})
