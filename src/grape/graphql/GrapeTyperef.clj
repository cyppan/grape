(ns grape.graphql.GrapeTypeRef
  (:gen-class :main false
              :name grape.graphql.GrapeTypeRef
              :extends graphql.schema.GraphQLTypeReference
              :state "state"
              :constructors {[String clojure.lang.Keyword clojure.lang.Keyword clojure.lang.Keyword] [String]}
              :init "init"))

(defn -init [name type resource-key field]
  [[name] [name type resource-key field]])
