(ns org.clojars.punit-naik.sql-batcher
  (:require [clojure.java.jdbc :as db]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn update-or-delete-op
  "Checks if the large query is doing an update or delete operation"
  [query]
  (if (str/starts-with? query "delete from")
    :delete :update))

(defn table-name
  "Gets the name of the table from the large update/delete query"
  [query]
  (let [query-parts (str/split query #"\s+")]
    (nth query-parts
         (if (= :delete (update-or-delete-op query))
           2 1))))

(defn primary-key-column
  "Assumes the primary key column to be `<table-name>_id`"
  [query]
  (str (table-name query) "_id"))

(defn remove-trailing-semicolon
  "Removes semi-colon from the end of a string"
  [s]
  (if (str/ends-with? s ";")
    (str/join (butlast s))
    s))

(defn where-clause
  "Extracts where clause from the large update/delete query"
  [query]
  (->> (str/split query #"where ")
       second
       str/trim
       str/trim-newline
       (#(str/split % #"and"))
       (map str/trim)
       (str/join " and ")))

(defn build-select-query
  [query pkey-column]
  (str "select " pkey-column
       " from " (table-name query)
       " where " (where-clause query)
       " order by " pkey-column
       " limit ?,?"))

(defn get-pkey-batch
  [db-spec select-query offset limit pkey-column]
  (db/query
   db-spec
   [select-query offset limit]
   {:row-fn (keyword pkey-column)}))

(defn where-in-clause
  [column-name values]
  (into [(str column-name
              " in ("
              (str/join "," (repeat (count values) "?"))
              ")")]
        values))

(def set-clause
  "Gets the set column names and their values from the update query"
  (memoize
   (fn [query]
     (-> query
         (str/split #" where ")
         first
         (str/split #"set ")
         second
         str/trim
         str/trim-newline
         (str/split #",")
         (->> (map
               (fn [s]
                 (let [[col-name value] (str/split s #"\=")
                       value (->> value str/trim-newline str/trim)]
                   [(->> col-name str/trim-newline str/trim keyword)
                    (if (re-matches #"[0-9]+" value) (read-string value) value)])))
              (into {}))))))

(defn execute!
  "Executes the update/delete query
   Returns the number of rows processed"
  [db-spec query pkey-column pkey-batch]
  (when (seq pkey-batch)
    (log/info (str "Executing query " query " for a batch of size " (count pkey-batch)))
    (let [table (keyword (table-name query))
          where (where-in-clause pkey-column pkey-batch)]
      (first
       (if (= :update (update-or-delete-op query))
         (db/update! db-spec table (set-clause query) where)
         (db/delete! db-spec table where))))))

(defn start-batch-processing!
  [db-spec query batch-size & [pkey-column]]
  (log/info "Starting the batch processing of large query:" query)
  (let [pkey-column (str/lower-case (or pkey-column (primary-key-column query)))
        query (-> query str/lower-case remove-trailing-semicolon)
        select-query (build-select-query query pkey-column)
        delete-op? (= :delete (update-or-delete-op query))]
    (loop [offset 0
           total-rows-processed 0
           finished? false]
      (if finished?
        (do (log/info "Total number of rows processed:" total-rows-processed)
            (log/info "Finished batch processing of query:" query))
        (let [batch (get-pkey-batch db-spec select-query offset batch-size pkey-column)
              batch-count (count batch)]
          (recur (if delete-op? offset (+ offset batch-count))
                 (+ total-rows-processed (or (execute! db-spec query pkey-column batch) 0))
                 (zero? batch-count)))))))