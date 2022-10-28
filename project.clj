(defproject org.clojars.punit-naik/sql-batcher "0.1.0-SNAPSHOT"
  :description "A Clojure library designed to run large sql updates/deletes in batches"
  :url "https://github.com/punit-naik/sql-batcher"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [com.taoensso/timbre "5.2.1"]]
  :repl-options {:init-ns org.clojars.punit-naik.sql-batcher})
