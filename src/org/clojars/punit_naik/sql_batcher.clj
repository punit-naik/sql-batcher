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
  (when-let [where (-> (re-matcher #"(where .* (and|or) .*)"
                                   query)
                       re-find
                       first)]
    (str/replace where #"\s+" " ")))

(defn join-clause
  [query]
  (when-let [join (-> (re-matcher #"(\w* join \w+ on .* \= .*)"
                                  query)
                      re-find
                      first)]
    (-> (str/replace join #" where.*" "")
        (str/replace #"\s+" " "))))

(defn build-select-query
  [query pkey-column]
  (let [join (join-clause query)
        where (where-clause query)]
    (str "select " pkey-column
         " from " (table-name query)
         (when join
           (str " " join))
         (when where
           (str " " where))
         " order by " pkey-column
         " limit ?")))

(defn get-pkey-batch
  [db-spec select-query limit pkey-column]
  (db/query
   db-spec
   [select-query limit]
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

(defn add-extra-where-clauses
  [query update-map]
  (reduce (fn [q [col value]]
            (str/replace
             q
             "where"
             (str "where " (name col) " != " value " and ")))
          query update-map))

(defn start-batch-processing!
  [db-spec query batch-size & [pkey-column select-query-override]]
  (log/info "Starting the batch processing of large query:" query)
  (let [pkey-column (str/lower-case (or pkey-column (primary-key-column query)))
        query (-> query str/lower-case remove-trailing-semicolon)
        select-query (cond-> (if (seq select-query-override)
                               (-> select-query-override
                                   remove-trailing-semicolon
                                   str/lower-case
                                   (str/replace #"limit [0-9]+" "")
                                   (str " limit ?"))
                               (build-select-query query pkey-column))
                       (not= :delete (update-or-delete-op query))
                       (add-extra-where-clauses (set-clause query)))]
    (loop [total-rows-processed 0
           finished? false]
      (if finished?
        (do (log/info "Total number of rows processed:" total-rows-processed)
            (log/info "Finished batch processing of query:" query))
        (let [batch (get-pkey-batch db-spec select-query batch-size pkey-column)
              batch-count (count batch)]
          (recur (+ total-rows-processed (or (execute! db-spec query pkey-column batch) 0))
                 (zero? batch-count)))))))