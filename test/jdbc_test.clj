(ns jdbc-test
  (:import org.postgresql.util.PGobject)
  (:require [jdbc :refer :all]
            [jdbc.transaction :refer :all]
            [jdbc.types :refer :all]
            [jdbc.impl :refer :all]
            [jdbc.proto :refer :all]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(def h2-dbspec1 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec2 {:subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec3 {:subprotocol "h2"
                 :subname "mem:"})

(def h2-dbspec4 {:subprotocol "h2"
                 :subname "mem:"
                 :isolation-level :serializable})

(def pg-dbspec {:subprotocol "postgresql"
                :subname "//localhost:5432/test"})

(def pg-dbspec-pretty {:vendor "postgresql"
                       :name "test"
                       :host "localhost"
                       :read-only true})

(deftest db-extra-returning-keys
  (testing "Testing basic returning keys"
    (with-connection [conn pg-dbspec]
      (execute! conn "DROP TABLE IF EXISTS foo_retkeys;")
      (execute! conn "CREATE TABLE foo_retkeys (id int primary key, num integer);")
      (let [sql (str "INSERT INTO foo_retkeys (id, num) VALUES (?, ?)")
            res (execute-prepared! conn sql [2, 0] [3, 0] {:returning [:id]})]
        (is (= res [{:id 2} {:id 3}])))))

  (testing "Testing returning keys with vector sql"
    (with-connection [conn pg-dbspec]
      (execute! conn "DROP TABLE IF EXISTS foo_retkeys;")
      (execute! conn "CREATE TABLE foo_retkeys (id int primary key, num integer);")
      (let [sql (str "INSERT INTO foo_retkeys (id, num) VALUES (?, ?)")
            res (execute-prepared! conn [sql 2 0] {:returning [:id]})]
        (is (= res [{:id 2}])))))

  (testing "Testing wrong arguments"
    (with-connection [conn pg-dbspec]
      (is (thrown? IllegalArgumentException
                   (let [sql (str "INSERT INTO foo_retkeys (id, num) VALUES (?, ?)")]
                     (execute-prepared! conn [sql 1 0] [2 0]))))))
)

(deftest db-specs
  (testing "Create connection with distinct dbspec"
    (let [c1 (make-connection h2-dbspec1)
          c2 (make-connection h2-dbspec2)
          c3 (make-connection h2-dbspec3)
          c4 (make-connection pg-dbspec-pretty)]
      (is (instance? jdbc.types.Connection c1))
      (is (instance? jdbc.types.Connection c2))
      (is (instance? jdbc.types.Connection c3))
      (is (instance? jdbc.types.Connection c4))))

  (testing "Using macro with-connection"
    (with-connection h2-dbspec3 conn
      (is (instance? jdbc.types.Connection conn)))))

(deftest db-isolation-level
  (testing "Using dbspec with :isolation-level"
    (let [c1 (make-connection h2-dbspec4)
          c2 (make-connection h2-dbspec3)]
      (is (= (:isolation-level c1) :serializable))
      (is (= (:isolation-level c2) nil))))

  (testing "Set isolation level on transaction"
    (let [func1 (fn [conn] (is (= (:isolation-level conn) :serializable)))
          func2 (fn [conn] (is (= (:isolation-level conn) :read-committed)))]
      (with-connection [conn h2-dbspec3]
        (call-in-transaction conn func1 {:isolation-level :serializable})
        (is (= (:isolation-level conn) nil)))

      (with-connection [conn h2-dbspec4]
        (call-in-transaction conn func2 {:isolation-level :read-committed})
        (is (= (:isolation-level conn) :serializable))))))

(deftest db-readonly-transactions
  (testing "Set readonly for transaction"
    (let [func (fn [conn]
                 (let [raw (:connection conn)]
                   (is (true? (.isReadOnly raw)))))]
      (with-connection [conn pg-dbspec]
        (call-in-transaction conn func {:read-only true})
        (is (false? (.isReadOnly (:connection conn)))))))

  (testing "Set readonly flag with with-transaction macro"
      (with-connection [conn pg-dbspec]
        (with-transaction conn {:read-only true}
          (is (true? (.isReadOnly (:connection conn)))))
        (is (false? (.isReadOnly (:connection conn)))))))

(deftest db-commands
  (testing "Simple create table"
    (with-connection h2-dbspec3 conn
      (let [sql "CREATE TABLE foo (name varchar(255), age integer);"
            r   (execute! conn sql)]
        (is (= (list 0) r)))))

  (testing "Create duplicate table"
     (with-connection h2-dbspec3 conn
       (let [sql "CREATE TABLE foo (name varchar(255), age integer);"]
         (execute! conn sql)
         (is (thrown? org.h2.jdbc.JdbcBatchUpdateException (execute! conn sql))))))

  (testing "Simple query result using with-query macro"
    (with-connection h2-dbspec3 conn
      (with-query conn results ["SELECT 1 + 1 as foo;"]
        (is (= [{:foo 2}] (doall results))))))

  (testing "Simple query result using query function"
    (with-connection h2-dbspec3 conn
      (let [result (query conn ["SELECT 1 + 1 as foo;"])]
        (is (= [{:foo 2}] result)))))

  (testing "More complex query using query funcion"
    (with-connection [conn pg-dbspec]
      (let [result (query conn ["SELECT * FROM generate_series(1, ?) LIMIT 1 OFFSET 3;" 10])]
        (is (= (count result) 1)))))

  (testing "Simple query result using query function overwriting identifiers parameter."
    (with-connection h2-dbspec3 conn
      (let [result (query conn ["SELECT 1 + 1 as foo;"] {:identifiers identity})]
        (is (= [{:FOO 2}] result)))))

  (testing "Simple query result using query function and string parameter"
    (with-connection h2-dbspec3 conn
      (let [result (query conn "SELECT 1 + 1 as foo;")]
        (is (= [{:foo 2}] result)))))

  (testing "Simple query result using query function as vectors of vectors"
    (with-connection h2-dbspec3 conn
      (let [result (query conn ["SELECT 1 + 1 as foo;"] {:as-rows? true})]
        (is (= [2] (first result))))))

  (testing "Pass prepared statement."
    (with-connection h2-dbspec3 conn
      (let [stmt    (make-prepared-statement conn ["SELECT 1 + 1 as foo;"])
            result  (query conn stmt {:as-rows? true})]
        (is (= [2] (first result))))))

  (testing "Low level query result"
    (with-open [conn    (make-connection h2-dbspec3)
                result  (make-query conn ["SELECT 1 + 1 as foo;"])]
      (is (instance? jdbc.types.ResultSet result))
      (is (instance? java.sql.ResultSet (:rs result)))
      (is (instance? java.sql.PreparedStatement (:stmt result)))
      (is (vector? (:data result)))
      (is (= [{:foo 2}] (doall (:data result))))))

  (testing "Low level query result with lazy off"
    (with-open [conn    (make-connection h2-dbspec3)]
      (with-transaction conn
        (let [result  (make-query conn ["SELECT 1 + 1 as foo;"] {:lazy true})]
          (is (instance? jdbc.types.ResultSet result))
          (is (instance? java.sql.ResultSet (:rs result)))
          (is (instance? java.sql.PreparedStatement (:stmt result)))
          (is (seq? (:data result)))
          (is (= [{:foo 2}] (doall (:data result))))))))

  (testing "Execute prepared"
    (with-connection h2-dbspec3 conn
      (execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (execute-prepared! conn "INSERT INTO foo (name,age) VALUES (?, ?);"
                         ["foo", 1]  ["bar", 2])

      (with-query conn results ["SELECT count(age) as total FROM foo;"]
        (is (= [{:total 2}] (doall results)))))))

(deftest db-execute-prepared-statement
  (testing "Execute simple sql based prepared statement."
    (with-connection h2-dbspec3 conn
      (execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (let [res (execute-prepared! conn "INSERT INTO foo (name,age) VALUES (?, ?);"
                                   ["foo", 1]  ["bar", 2])]
        (is (= res (seq [1 1]))))))
  (testing "Executing self defined prepared statement"
    (with-connection [conn h2-dbspec3]
      (execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (let [stmt (make-prepared-statement conn "INSERT INTO foo (name,age) VALUES (?, ?);")
            res1  (execute-prepared! conn stmt ["foo", 1] ["bar", 2])
            res2  (execute-prepared! conn stmt ["fooo", 1] ["barr", 2])]
        (with-query conn results ["SELECT count(age) as total FROM foo;"]
          (is (= [{:total 4}] (doall results))))))))

(deftest db-commands-bytes
  (testing "Insert bytes"
    (let [buffer       (byte-array (map byte (range 0 10)))
          inputStream  (java.io.ByteArrayInputStream. buffer)
          sql          "CREATE TABLE foo (id integer, data bytea);"]
      (with-connection h2-dbspec3 conn
        (execute! conn sql)
        (let [res (execute-prepared! conn "INSERT INTO foo (id, data) VALUES (?, ?);" [1 inputStream])]
          (is (= res '(1))))

        (let [res (query conn "SELECT * FROM foo")
              res (first res)]
          (is (instance? (Class/forName "[B") (:data res)))
          (is (= (get (:data res) 2) 2)))))))

(extend-protocol ISQLType
  (class (into-array String []))

  (set-stmt-parameter! [this conn stmt index]
    (let [raw-conn        (:connection conn)
          prepared-value  (as-sql-type this conn)
          array           (.createArrayOf raw-conn "text" prepared-value)]
      (.setArray stmt index array)))

  (as-sql-type [this conn] this))

(deftest db-commands-custom-types
  (testing "Test use arrays"
    (with-connection pg-dbspec conn
      (with-transaction conn
        (set-rollback! conn)
        (let [sql "CREATE TABLE arrayfoo (id integer, data text[]);"
              dat (into-array String ["foo", "bar"])]
          (execute! conn sql)
          (let [res (execute-prepared! conn "INSERT INTO arrayfoo (id, data) VALUES (?, ?);" [1, dat])]
            (is (= res '(1))))
          (let [res (first (query conn "SELECT * FROM arrayfoo"))]

            (let [rr (.getArray (:data res))]
              (is (= (count rr) 2))
              (is (= (get rr 0) "foo"))
              (is (= (get rr 1) "bar")))))))))

(defrecord BasicTransactionStrategy []
  ITransactionStrategy
  (begin! [_ conn opts]
    (let [depth    (:depth-level conn)
          raw-conn (:connection conn)]
      (if depth
        (assoc conn :depth-level (inc (:depth-level conn)))
        (let [prev-autocommit-state (.getAutoCommit raw-conn)]
          (.setAutoCommit raw-conn false)
          (assoc conn :depth-level 0 :prev-autocommit-state prev-autocommit-state)))))

  (rollback! [_ conn opts]
    (let [depth    (:depth-level conn)
          raw-conn (:connection conn)]
      (if (= depth 0)
        (do
          (.rollback raw-conn)
          (.setAutoCommit raw-conn (:prev-autocommit-state conn))
          (dissoc conn :depth-level :prev-autocommit-state)))))

  (commit! [_ conn opts]
    (let [depth    (:depth-level conn)
          raw-conn (:connection conn)]
      (if (= depth 0)
        (do
          (.commit raw-conn)
          (.setAutoCommit raw-conn (:prev-autocommit-state conn))
          (dissoc :depth-level :prev-autocommit-state))))))

(defrecord DummyTransactionStrategy []
  ITransactionStrategy
  (begin! [_ conn opts] conn)
  (rollback! [_ conn opts] nil)
  (commit! [_ conn opts] nil))

(deftest db-transaction-strategy
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]
    (testing "Test dummy transaction strategy"
      (with-connection h2-dbspec3 conn
        (with-transaction-strategy conn (DummyTransactionStrategy.)
          (is (instance? DummyTransactionStrategy (:transaction-strategy conn)))
          (execute! conn sql1)
          (try
            (with-transaction conn
              (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
              (let [results (query conn sql3)]
                (is (= (count results) 2))
                (throw (RuntimeException. "Fooo"))))
            (catch Exception e
              (let [results (query conn sql3)]
                (is (= (count results) 2))))))))
    (testing "Test simple transaction strategy"
      (with-open [conn (-> (make-connection h2-dbspec3)
                           (wrap-transaction-strategy (BasicTransactionStrategy.)))]
        (is (instance? BasicTransactionStrategy (:transaction-strategy conn)))
        (execute! conn sql1)
        (try
          (with-transaction conn
            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (is (= (:depth-level conn) 0))
            (with-transaction conn
              (is (= (:depth-level conn) 1))
              (let [results (query conn sql3)]
                (is (= (count results) 2))
                (throw (RuntimeException. "Fooo")))))
          (catch Exception e
            (let [results (query conn sql3)]
              (is (= (count results) 0)))))))))


(deftest db-transactions
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]

    (testing "Basic transaction test with exception."
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (try
          (with-transaction conn
            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (with-query conn results [sql3]
              (is (= (count results) 2))
              (throw (RuntimeException. "Fooo"))))
          (catch Exception e
            (with-query conn results [sql3]
              (is (= (count results) 0)))))))

    (testing "Basic transaction test without exception."
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2]))
        (with-transaction conn
          (with-query conn results [sql3]
            (is (= (count results) 2))))))

    (testing "Immutability"
      (with-connection h2-dbspec3 conn
        (with-transaction conn
          (is (:in-transaction conn))
          (is (:rollback conn))
          (is (false? @(:rollback conn)))
          (is (nil? (:savepoint conn))))
        (is (= (:in-transaction conn) nil))
        (is (= (:rollback conn) nil))))

    (testing "Set savepoint"
      (with-connection h2-dbspec3 conn
        (with-transaction conn
          (is (:in-transaction conn))
          (with-transaction conn
            (is (not= (:savepoint conn) nil))))))

    (testing "Set rollback 01"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
          (is (false? @(:rollback conn)))

          (with-transaction conn
            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (set-rollback! conn)

            (is (true? @(:rollback conn)))

            (let [results (query conn sql3)]
              (is (= (count results) 4))))

          (with-query conn results [sql3]
            (is (= (count results) 2))))))

    (testing "Set rollback 02"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (set-rollback! conn)
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])

          (is (true? @(:rollback conn)))

          (with-transaction conn
            (is (false? @(:rollback conn)))

            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (let [results (query conn sql3)]
              (is (= (count results) 4))))

          (with-query conn results [sql3]
            (is (= (count results) 4))))

        (with-query conn results [sql3]
            (is (= (count results) 0)))))

    (testing "Subtransactions"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])

          (try
            (with-transaction conn
              (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
              (with-query conn results [sql3]
                (is (= (count results) 4))
                (throw (RuntimeException. "Fooo"))))
            (catch Exception e
              (with-query conn results [sql3]
                (is (= (count results) 2))))))))))

;; PostgreSQL json support

(extend-protocol ISQLType
  clojure.lang.IPersistentMap
  (set-stmt-parameter! [self conn stmt index]
    (.setObject stmt index (as-sql-type self conn)))
  (as-sql-type [self conn]
    (doto (PGobject.)
      (.setType "json")
      (.setValue (json/generate-string self)))))

(extend-protocol ISQLResultSetReadColumn
  PGobject
  (from-sql-type [pgobj conn metadata i]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/parse-string value)
        :else value))))

(deftest db-postgresql-json-type
  (testing "Persist/Query json fields"
    (with-connection [conn pg-dbspec]
      (with-transaction conn
        (set-rollback! conn)
        (let [sql-create "CREATE TABLE jsontest (data json);"
              sql-query  "SELECT data FROM jsontest;"
              sql-insert "INSERT INTO jsontest (data) VALUES (?);"]
          (execute! conn sql-create)
          (execute-prepared! conn sql-insert [{:foo "bar"}])
          (let [res (first (query conn sql-query))]
            (is (= res {:data {"foo" "bar"}}))))))))
