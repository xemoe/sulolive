(ns flipmunks.budget.ui.datepicker
  (:require [om.next :as om :refer-macros [defui]]
            [flipmunks.budget.ui :refer [style]]
            [cljsjs.pikaday]
            [sablono.core :refer-macros [html]]
            [garden.core :refer [css]]))

(def pikaday-ref-name "pikaday")

(defn get-pikaday-dom-node [this]
  (.getDOMNode (om/react-ref this pikaday-ref-name)))

(defn set-date-if-changed [this new-date old-date]
  (let [new-time (when new-date (.getTime new-date))
        old-time (when old-date (.getTime old-date))]
    (when (not= new-time old-time)
      (if new-date
        ;; pass true to avoid calling onSelect
        (some-> (om/get-state this) ::picker deref (.setDate new-date true))
        ;; workaround for pikaday not clearing value when date set to falsey
        (.value (get-pikaday-dom-node this) "")))))

(defui DatePicker
       Object
       (getInitialState [this] {::picker (atom nil)})
       (componentDidMount
         [this]
         (let [{:keys [on-change value]} (om/props this)
               {:keys [::picker]} (om/get-state this)
               p (js/Pikaday.
                   #js {:field    (get-pikaday-dom-node this)
                        :format   "D MMM YYYY"
                        :onSelect on-change})]
           (reset! picker p)
           (set-date-if-changed this value nil)))
       (componentWillUnmount [this] (some-> (om/get-state this)
                                            (::picker)
                                            (reset! nil)))
       (componentWillReceiveProps [this next-props]
                                  (let [{old-value :value} (om/props this)
                                        {next-value :value} next-props]
                                    (set-date-if-changed this next-value old-value)))
       (render [this]
               (html [:input {:type "text"
                              :ref pikaday-ref-name
                              :placeholder (-> this om/props :placeholder)}])))

(def datepicker (om/factory DatePicker))
