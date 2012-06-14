(defproject cwscawa "0.1.0-SNAPSHOT"
            :description "Clojure Web Service Cache Aware Work"
            :dependencies [[org.clojure/clojure "1.4.0"]
                           [org.clojure/data.priority-map "0.0.1"]
                           [noir "1.3.0-beta3"]
                           [cheshire "2.0.2"]]
            :plugins [[lein-marginalia "0.7.1"]]
            :main cwscawa.server)

