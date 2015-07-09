(ns example.db
  (:require [clojure.java.jdbc :as jdbc])
  (:import [java.util Date]
           [org.h2.jdbc JdbcBatchUpdateException]))

(def db {:classname "org.h2.Driver"
         :subname "mem:example"
         :subprotocol "h2"
         :create true})

(def conn (jdbc/get-connection db))

(jdbc/db-do-commands db
  (jdbc/create-table-ddl :heroes
                         [:id :int "PRIMARY KEY AUTO_INCREMENT"]
                         [:name "VARCHAR(32)"]
                         [:birthplace "VARCHAR(32)"]
                         [:created :timestamp]
                         :table-spec "")
  (jdbc/create-table-ddl :achievements
                         [:id :int "PRIMARY KEY AUTO_INCREMENT"]
                         [:name "VARCHAR(32)"]
                         [:hero :int "REFERENCES heroes (id)"]
                         [:created :timestamp]
                         :table-spec ""))


(defn insert-hero!
  [name birthplace]
  (let [timestamp (Date.)
        result (jdbc/with-db-transaction [t db]
                 (jdbc/insert! t :heroes [:name :birthplace :created] [name birthplace timestamp])
                 (jdbc/query t ["SELECT * FROM heroes WHERE name = ? AND birthplace = ? AND created = ?" name birthplace timestamp]))]
    (first result)))


(defn get-hero
  [id]
  (when (integer? id)
    (first (jdbc/query db ["SELECT * FROM heroes WHERE id = ?" id]))))


(defn find-heroes
  []
  (jdbc/query db ["SELECT * FROM heroes"]))


(defn remove-hero!
  [id]
  (if (integer? id)
    (jdbc/with-db-transaction [t db]
      (jdbc/delete! t :achievements ["hero = ?" id])
      (jdbc/delete! t :heroes ["id = ?" id]))
    false))

(defn insert-achievement!
  [name hero]
  (when (integer? hero)
    (let [timestamp (Date.)
          result (try
                   (jdbc/with-db-transaction [t db]
                     (jdbc/insert! t :achievements [:name :hero :created] [name hero timestamp])
                     (jdbc/query t ["SELECT * FROM achievements WHERE name = ? AND hero = ? AND created = ?" name hero timestamp]))
                   (catch JdbcBatchUpdateException t [t]))]
      (first result))))

(defn find-achievements
  ([]
   (jdbc/query db ["SELECT * FROM achievements"]))
  ([hero]
   (when (integer? hero)
     (jdbc/query db ["SELECT * FROM achievements WHERE hero = ?" hero]))))

(defn get-achievement
  [id]
  (when (integer? id)
    (first (jdbc/query db ["SELECT * FROM achievements WHERE id = ?" id]))))

(defn get-achievement-hero
  [id]
  (when (integer? id)
    (let [q (str "SELECT heroes.id AS id, heroes.name AS name, heroes.created AS created "
                 "FROM heroes, achievements "
                 "WHERE achievements.id = ? AND heroes.id = achievements.id")]
      (first (jdbc/query db [q id])))))
