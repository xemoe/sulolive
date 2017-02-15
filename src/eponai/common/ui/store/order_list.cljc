(ns eponai.common.ui.store.order-list
  (:require
    [eponai.client.routes :as routes]
    [eponai.common.ui.dom :as my-dom]
    [eponai.common.ui.elements.css :as css]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))

(defui OrderList
  static om/IQuery
  (query [_]
    [:query/orders])
  Object
  (render [this]
    (let [{:keys [store]} (om/get-computed this)
          {:keys [query/orders]} (om/props this)]
      (dom/div nil
        (my-dom/div
          (->> (css/grid-row))
          (my-dom/div
            (->> (css/grid-column)
                 (css/text-align :right))
            (dom/a #js {:href      (routes/url :store-dashboard/create-order {:store-id (:db/id store)
                                                                              :action "create"})
                        :className "button"} "Create Order")))

        (my-dom/div
          (->> (css/grid-row))
          (my-dom/div
            (->> (css/grid-column))
            (dom/table
              #js {:className "hover"}
              (dom/thead
                nil
                (dom/tr nil
                        (dom/th nil "ID")
                        (dom/th nil "Amount")
                        (dom/th nil "Last Updated")))
              (dom/tbody
                nil
                (map (fn [o]
                       (let [product-link (routes/url :store-dashboard/order
                                                      {:store-id (:db/id store)
                                                       :order-id (:order/id o)})]
                         (dom/tr nil
                                 (dom/td nil
                                         (dom/a #js {:href product-link}
                                                (dom/span nil (:order/id o))))
                                 (dom/td nil
                                         (dom/a #js {:href product-link}
                                                (dom/span nil (:order/amount o))))
                                 (dom/td nil
                                         (dom/a #js {:href product-link}
                                                (dom/span nil (:order/update o)))))))
                     orders)))))))))

(def ->OrderList (om/factory OrderList))