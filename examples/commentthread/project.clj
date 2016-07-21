(defproject commentthread "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [grape "0.1.0-SNAPSHOT"]
                 [com.novemberain/monger "3.0.2"]
                 [com.taoensso/timbre "4.7.0"]
                 [com.fzakaria/slf4j-timbre "0.3.2"]
                 [ring/ring-core "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [jumblerg/ring.middleware.cors "1.0.1"]
                 [ring/ring-json "0.4.0"]]
  :main ^:skip-aot commentthread.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
