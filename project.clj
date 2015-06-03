(defproject argo "0.1.0-SNAPSHOT"
  :description "JSON API implementation for Clojure"
  :url "http://github.com/stephenmuss/argo"
  :license {:name "MIT License"
            :url "https://github.com/stephenmuss/argo/blob/master/LICENSE"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [clj-time "0.9.0"]
                 [com.taoensso/timbre "3.4.0"]
                 [compojure "1.3.4"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-json "0.3.1"]])
