(ns eponai.server.external.email
  (:require
    [hiccup.page :refer [xhtml]]
    [eponai.server.external.email.templates :as templates]
    [postal.core :as postal]
    [taoensso.timbre :refer [debug info]]))

(defprotocol ISendEmail
  (-send-order-receipt [this params]))

(defrecord SendEmail [smtp]
  ISendEmail
  (-send-order-receipt [this {:keys [charge order] :as params}]
    (debug "Sending email... " smtp)
    (let [{:keys [source created amount]} charge
          {:keys [last4 brand]} source
          {:order/keys [store shipping user]} order
          {store-name :store.profile/name} (:store/profile store)
          sent-email (postal/send-message smtp
                                          {:from    "SULO Live <hello@sulo.live>"
                                           :to      (:user/email user)
                                           :subject (str "Your SULO Live receipt from " store-name " #" (:db/id order))
                                           :body    [{:type    "text/html"
                                                      :content (templates/receipt params)}]})]
      (debug sent-email))))

(defn email [smtp]
  (->SendEmail smtp))

(defn email-stub []
  (reify
    ISendEmail
    (-send-order-receipt [_ {:keys [charge order]}]
      (let [{:keys [source created amount]} charge
            {:keys [last4 brand]} source
            {:order/keys [store shipping user]} order
            {store-name :store.profile/name} (:store/profile store)]
        (info "Fake email - send order receipt: " {:to        (:user/email user)
                                                   :subject   (str "Your SULO Live receipt from " store-name " #" (:db/id order))
                                                   :order?    (some? order)
                                                   :charge?   (some? charge)
                                                   :shipping? (some? shipping)
                                                   :store?    (some? store)})))))
