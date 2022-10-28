(ns org.clojars.punit-naik.sql-batcher-test
  (:require [clojure.java.jdbc :as db]
            [clojure.test :refer [deftest is use-fixtures]]
            [org.clojars.punit-naik.in-memory-sql :as imsql]
            [org.clojars.punit-naik.sql-batcher :as sb]))

(defonce delete-query "delete from x where a > 2 and  a < 5")
(defonce update-query "update x set b  =  1 where a > 1  and a < 5")

(defonce db (atom nil))

(defonce create-table-query "CREATE TABLE x (a INT PRIMARY KEY, b INT NOT NULL)")
(defonce insert-values-query "INSERT INTO x VALUES(1, 1),(2, 2),(3,3),(4,4),(5,5),(6,6),(7,7),(8,8),(9,9),(10,10)")
(defonce drop-table-query "DROP TABLE x")

(defn sys-fix
  [f]
  (reset! db (imsql/start create-table-query insert-values-query))
  (f)
  (imsql/stop @db)
  (reset! db nil))

(use-fixtures :once sys-fix)

(defn- restore-state
  "Restores initial state of table `x`"
  []
  (db/execute! @db [drop-table-query])
  (db/execute! @db [create-table-query])
  (db/execute! @db [insert-values-query]))

(deftest update-or-delete-op-test
  (is (= :delete (sb/update-or-delete-op delete-query)))
  (is (= :update (sb/update-or-delete-op update-query))))

(deftest table-name-test
  (is (= "x" (sb/table-name delete-query)))
  (is (= "x" (sb/table-name update-query))))

(deftest primary-key-column-test
  (is (= "x_id" (sb/primary-key-column delete-query)))
  (is (= "x_id" (sb/primary-key-column update-query))))

(deftest remove-trailing-semicolon-test
  (is (= "punit naik" (sb/remove-trailing-semicolon "punit naik;")))
  (is (= "punit; naik" (sb/remove-trailing-semicolon "punit; naik;"))))

(deftest extract-where-clause-test
  (is (= "a > 2 and a < 5" (sb/extract-where-clause delete-query)))
  (is (= "a > 1 and a < 5" (sb/extract-where-clause update-query))))

(deftest build-select-query-test
  (is (= "select x_id from x where a > 2 and a < 5 order by x_id limit ?,?" (sb/build-select-query delete-query "x_id")))
  (is (= "select x_id from x where a > 1 and a < 5 order by x_id limit ?,?" (sb/build-select-query update-query "x_id"))))

(deftest get-pkey-batch-test
  (is (= [3] (sb/get-pkey-batch @db (sb/build-select-query delete-query "a") 0 1 "a")))
  (is (= [3 4] (sb/get-pkey-batch @db (sb/build-select-query delete-query "a") 0 4 "a")))
  (is (= [2] (sb/get-pkey-batch @db (sb/build-select-query update-query "a") 0 1 "a")))
  (is (= [2 3 4] (sb/get-pkey-batch @db (sb/build-select-query update-query "a") 0 4 "a"))))

(deftest where-in-clause-test
  (is (= ["a in (?,?,?,?)" 1 2 3 4] (sb/where-in-clause "a" [1 2 3 4])))
  (is (= ["b in (?,?,?,?)" 5 6 7 8] (sb/where-in-clause "b" [5 6 7 8]))))

(deftest set-clause-test
  (is (thrown? NullPointerException (sb/set-clause delete-query)))
  (is (= {:b 1} (sb/set-clause update-query))))

(deftest execute!-test
  (is (= 3 (sb/execute! @db update-query "a" [2 3 4])))
  (is (= [1 1 1] (db/query @db ["select b from x where a > 1 and a < 5"] {:row-fn :b})))
  (is (= 2 (sb/execute! @db delete-query "a" [3 4])))
  (is (= [] (db/query @db ["select b from x where a > 2 and a < 5"] {:row-fn :b})))
  (restore-state))

(deftest start-batch-processing!-test
  (sb/start-batch-processing! @db update-query 1 "a")
  (is (= [1 5 6 7 8 9 10] (db/query @db ["select distinct b from x"] {:row-fn :b})))
  (sb/start-batch-processing! @db delete-query 1 "a")
  (is (= 8 (db/query @db ["select count(a) a_count from x"] {:row-fn :a_count :result-set-fn first}))))