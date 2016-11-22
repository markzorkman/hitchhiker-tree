(defproject hitchhiker-tree "0.1.0-SNAPSHOT"
  :description "A Hitchhiker Tree Library"
  :url "https://github.com/dgrnbrg/hitchhiker-tree"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.memoize "0.5.9"]
                 [com.taoensso/carmine "2.15.0"]
                 [org.clojure/core.rrb-vector "0.0.11"]
                 [org.clojure/data.avl "0.0.17"]
                 [org.clojure/core.cache "0.6.5"]]
  :aliases {"bench" ["with-profile" "profiling" "run" "-m" "hitchhiker.bench"]}
  :jvm-opts ["-server" "-Xmx3700m" "-Xms3700m"]
  :profiles {:test
             {:dependencies [[org.clojure/test.check "0.9.0"]]}
             :profiling
             {:main hitchhiker.bench
              :source-paths ["env/profiling"]
              :dependencies [[criterium "0.4.4"]
                             [org.clojure/tools.cli "0.3.5"]
                             [org.clojure/test.check "0.9.0"]
                             [com.infolace/excel-templates "0.3.3"]]}})
