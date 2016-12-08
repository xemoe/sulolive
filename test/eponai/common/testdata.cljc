(ns eponai.common.testdata
  #?(:clj (:require
             [datomic.api :as d]
             [eponai.server.datomic-dev :as datomic_dev]
             [eponai.server.datomic.query :as pull])
     :cljs (:require-macros
             [eponai.common.testdata :refer [inline-datomic-schema]]))
  (:require
    [eponai.common.datascript :as eponai.datascript]
    [eponai.common.database :as db]))

#?(:clj
   (defmacro inline-datomic-schema
     "Compiles our schema-0.edn into a clojurescript file (testdata.cljs)
     so we can use it in our tests."
     []
     (when-let [schema (seq (datomic_dev/read-schema-files))]
       (let [conn# (datomic_dev/create-new-inmemory-db "read-datomic-schema")
             _ (db/transact conn# schema)
             inlined# (into [] (pull/schema (d/db conn#)))]
         inlined#))))

(def datomic-schema (inline-datomic-schema))

(defn datascript-schema [] (eponai.datascript/schema-datomic->datascript datomic-schema))
