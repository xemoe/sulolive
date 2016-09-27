(ns eponai.client.utils
  (:require [datascript.db :as db]
            [datascript.core :as d]
            [clojure.data :as diff]
            [eponai.common.datascript :as common.datascript]
            [om.next :as om]
    #?(:cljs [taoensso.timbre :as timbre :refer-macros [debug warn error]]
       :clj  [taoensso.timbre :as timbre :refer [debug warn error]])
    #?@(:cljs
        [[goog.log :as glog]
         [cljs-time.coerce :as cljs-time]
         [eponai.client.logger :as logger]
         [devtools.core :as devtools]
         [goog.date]]))
  #?(:clj (:import [datascript.db DB])))

;; ---------------------
;; -- App initialization

(defonce reconciler-atom (atom nil))

(defonce conn-atom (atom nil))

(defn init-conn
  "Sets up the datascript state. Caches the state so we can keep our app state between
  figwheel reloads."
  []
  (if @conn-atom
    (do
      (debug "Reusing old conn. It currently has schema for attributes:" (-> @conn-atom deref :schema keys))
      @conn-atom)
    (let [ui-state [{:ui/singleton :ui.singleton/app}
                    {:ui/singleton :ui.singleton/auth}
                    {:ui/component                      :ui.component/project
                     :ui.component.project/selected-tab :dashboard}
                    {:ui/component :ui.component/widget}
                    {:ui/component :ui.component/root}
                    {:ui/component :ui.component/mutation-queue}
                    {:ui/component :ui.component/sidebar
                     :ui.component.sidebar/newsletter-subscribe-status
                                   :ui.component.sidebar.newsletter-subscribe-status/not-sent}]
          conn (d/create-conn (common.datascript/ui-schema))]
      (d/transact! conn ui-state)
      (reset! conn-atom conn))))

;; --------------------------
;; -- Mutation queue protocol

(defprotocol IQueueMutations
  (mutations [this])
  (queue-mutation [this id mutation])
  (copy-queue [this other])
  (mutations-after [this id is-remote-fn])
  (keep-mutations-after [this id is-remote-fn])
  (clear-queue [this]))

;; TODO: Store the mutation queue as flat entities, instead of
;;       deep objects with order importance.

(defn- mutation-queue-entity [db]
  {:post [(some? %)]}
  (d/entity db [:ui/component :ui.component/mutation-queue]))

(defn- update-queue [db update-fn]
  (d/db-with db (vector (-> (into {} (mutation-queue-entity db))
                            (update :ui.component.mutation-queue/queue update-fn)))))

(defn- mutation-queue [db]
  (:ui.component.mutation-queue/queue (mutation-queue-entity db)))

(defn- queue-contains-id? [queue id]
  (and (some? id)
       (some #(= id (:id %)) queue)))

(defn- drop-id-xf [id]
  (comp (drop-while #(not= id (:id %)))
        (drop-while #(= id (:id %)))))

(defn- local-mutations-with-id [db id is-remote-fn]
  (into [] (comp (filter #(= id (:id %)))
                 (remove #(is-remote-fn (:mutation %))))
        (mutation-queue db)))

(extend-protocol IQueueMutations
  #?(:clj DB :cljs db/DB)
  (mutations [this] (mapv :mutation (mutation-queue this)))
  (copy-queue [this other]
    (reduce (fn [o {:keys [id mutation]}]
              (queue-mutation o id mutation))
            other
            (mutation-queue this)))
  (queue-mutation [this id mutation]
    (let [queue (mutation-queue this)]
      (when (and (queue-contains-id? queue id)
                 (not= id (:id (last queue))))
        (warn
          "Queue was in an invalid state. Queue should either not contain"
          " the mutation id, or the last item in the queue should have the"
          " same mutation id. Queue: " queue " id: " id " mutation: " mutation)))
    (update-queue this (fnil (fn [q] (conj q {:id id :mutation mutation}))
                             [])))
  (keep-mutations-after [this id is-remote-fn]
    (cond-> this
            (queue-contains-id? (mutation-queue this) id)
            (update-queue (fn [q]
                            (into (local-mutations-with-id this id is-remote-fn)
                                  (drop-id-xf id)
                                  q)))))
  (clear-queue [this]
    (d/db-with this [[:db.fn/retractAttribute
                      [:ui/component :ui.component/mutation-queue]
                      :ui.component.mutation-queue/queue]]))
  (mutations-after [this id is-remote-fn]
    (let [queue (mutation-queue this)]
      (cond->> queue
               (queue-contains-id? queue id)
               (into (local-mutations-with-id this id is-remote-fn)
                     (comp (drop-id-xf id)
                           (map :mutation)))))))

;; ------ Dates -----------
;; Extends equality on goog.date's to speed up shouldComponentUpdate.

#?(:cljs
   (defn date-eq [a b]
     (and (satisfies? cljs-time/ICoerce a)
          (satisfies? cljs-time/ICoerce b)
          (= (cljs-time/to-long a)
             (cljs-time/to-long b)))))


#?(:cljs
   (extend-protocol IEquiv
     goog.date.Date
     (-equiv [this other] (date-eq this other))

     goog.date.DateTime
     (-equiv [this other] (date-eq this other))

     goog.date.UtcDateTime
     (-equiv [this other] (date-eq this other))))

;; ------ Util functions -----------

(defn distinct-by
  "Distinct by (f input). See clojure.core/distinct."
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [by (f input)]
            (if (contains? @seen by)
              result
              (do (vswap! seen conj by)
                  (rf result input)))))))))
  ([f coll]
   (sequence (distinct-by f) coll)))

;; -------------------
;; -- Cached ui->props

(defn traverse-query-path
  "Takes a query and a path. Returns subqueries matching the path.

  Will return a single subquery for queries without unions.
  A path may match any of the union's branches/values."
  [query path]
  (letfn [(step-into-query [query part]
            (cond
              (= part ::root)
              [query]

              (number? part)
              [query]

              (keyword? query)
              nil

              (map? query)
              (when (contains? query part)
                (let [query' (get query part)]
                  (if (map? query')
                    ;; Union. Could be any of the values in an union.
                    ;; Hack: Take the first matching value.
                    ;; TODO: Try to match all values, making traverse-query
                    ;;       return multiple values.
                    (vals query')
                    ;; Anything else, just traverse as normal.
                    [query'])))

              (sequential? query)
              (mapcat #(step-into-query % part) query)

              :else
              (do (debug "not traversing query: " query " path-part: " part)
                  nil)))]
    (reduce (fn [q p] (into [] (comp (mapcat #(step-into-query % p))
                                     (filter some?))
                            q))
            [query]
            path)))

(defn path->paths [path]
  (->> path
       (iterate rest)
       (take-while seq)
       (map vec)))

(defn- find-cached-props
  "Given a cache with map of path->(query, props), a component and a component's full query,
  check the cache if the component's full query has already been parsed (read).

  Returns nil or props for a component."
  [cache c-path c-query]
  (let [exact-subquery (traverse-query-path c-query c-path)
        _ (when-not (= 1 (count exact-subquery))
            (warn "Exact subquery was not an only match! Was: " exact-subquery))
        exact-subquery (first exact-subquery)
        find-props (fn [{:keys [::query ::props]}]
                     (let [subqueries (traverse-query-path query c-path)]
                       (when (some #(= % exact-subquery) subqueries)
                         (let [c-props (get-in props c-path)]
                           (when (some? c-props)
                             (debug "found cached props for c-path: " c-path))
                           c-props))))
        ret (->> (butlast c-path)
                 (path->paths)
                 (cons [::root])
                 (map #(get-in cache %))
                 (filter some?)
                 (some find-props))]
    ret))

(def om-path #'om/path)
(defn current-time []
  #?(:cljs (system-time)
     :clj (System/currentTimeMillis)))

(defn cached-ui->props
  "Takes an atom to store queries->props, app-state, component, full query of
  the component and a thunk for calling the parser for cache misses.
  Uses the query and the components path to find queries already parsed.

  Example:
  Let's say we've got components A and B.
  B is a subquery of A, i.e. query A: [... (om/get-query B) ...]
  When we parse A, we'll get the props of both A and B.
  We can get B's props by using (om/path B) in props of A.
  This is what we're doing with (find-cached-props ...)."
  [cache state component query parser-thunk]
  {:pre [(instance? #?(:clj clojure.lang.IDeref :cljs IDeref) cache)
         (instance? #?(:clj clojure.lang.IDeref :cljs IDeref) state)
         (om/component? component)
         (vector? query)
         (fn? parser-thunk)]}
  (let [path (om-path component)
        db (d/db state)
        cache-db (::db @cache)]
    (when-not (identical? db cache-db)
      ;; db's are not identical. Reset the cache.
      (reset! cache {::db db}))
    (let [props (or (find-cached-props @cache path query)
                    (parser-thunk))]
      (swap! cache update-in
             (or (seq path) [::root])
             merge
             {::query query
              ::props props})
      props)))

(defn cached-ui->props-fn
  "Takes a component and returns its props, just like om.next/default-ui->props.
  This function will also cache a component's props based on it's path and query
  to provide fast lookups for subcomponents, skipping a lot of reads."
  [parser]
  (let [cache (atom {})]
    (fn [env c]
      {:pre [(map? env) (om/component? c)]}
      (let [fq (om/full-query c)]
        (when-not (nil? fq)
          (let [s (current-time)
                ui (cached-ui->props cache (:state env) c fq #(-> (parser env fq)
                                                                  (get-in (om-path c))))
                e (current-time)]
            (when-let [l (:logger env)]
              (let [dt (- e s)]
                (when (< 16 dt)
                  (#?(:cljs glog/warning :clj prn) l (str (pr-str c) " query took " dt " msecs")))))
            ui))))))

(defn debug-ui->props-fn
  "Debug if our function is not acting as om.next's default ui->props function."
  [parser]
  (let [eponai-fn (cached-ui->props-fn parser)]
    (fn [env c]
      (let [om-ui (om/default-ui->props env c)
            eponai-ui (eponai-fn env c)]
        (when (not= om-ui eponai-ui)
          (warn "Om and eponai UI differ for component: " (pr-str c) " diff: " (diff/diff om-ui eponai-ui)))
        eponai-ui))))

;; ------ App initialization -----------

(defn set-level [l]
  (timbre/set-level! l))

(def set-trace #(set-level :trace))
(def set-debug #(set-level :debug))

#?(:cljs
   (defn install-app []
     (enable-console-print!)
     (devtools/install!)
     (logger/install-logger!)))