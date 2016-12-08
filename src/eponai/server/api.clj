(ns eponai.server.api
  (:require [clj-time.core :as time]
            [clj-time.coerce :as c]
            [clojure.core.async :refer [go >! <! chan put!]]
            [compojure.core :refer :all]
            [datomic.api :as d]
            [eponai.common.database :as db]
            [eponai.common.database.query :as pull]
            [eponai.common.format :as common.format]
            [eponai.server.datomic.format :as datomic.format]
            [eponai.server.external.stripe :as stripe]
            [eponai.server.external.mailchimp :as mailchimp]
            [eponai.server.http :as http]
            [taoensso.timbre :refer [debug error info]]
            [environ.core :refer [env]]
            [clj-time.core :as t])
  (:import (datomic Connection)))

;(defn currency-infos
;  "Post information about currencies with a map of the form:
; {:SGD {:symbol \"SGD\", :symbol_native \"$\", :decimal_digits 2, :rounding 0.0, :code \"SGD\"}},"
;  [conn cur-infos]
;  (let [db-cur-codes (->> (p/currencies (d/db conn))
;                          (map #(keyword (:currency/code %)))
;                          set)
;        cur-infos (->> cur-infos
;                       (filter #(contains? db-cur-codes (key %))))]
;    (transact conn (df/currencies {:currency-infos (vals cur-infos)}))))

; Actions

(defn link-fb-user-to-current-account [conn {:keys [user-uuid fb-user]}]
  (let [user (db/lookup-entity (d/db conn) [:user/uuid user-uuid])
        formatted-fb-user (datomic.format/fb-user user fb-user)]
    (debug "Transacting formatted fb-user: " formatted-fb-user)
    (db/transact-one conn formatted-fb-user)))

(defn api-error [status code ex-data]
  (ex-info (str "API error code " code)
           (merge {:status status
                   :code code}
                  ex-data)))

(defn facebook-disconnect [conn {:keys [user-uuid]}]
  (when-let [fb-user (db/one-with (d/db conn) {:where   '[[?u :user/uuid ?uuid]
                                                         [?e :fb-user/user ?u]]
                                              :symbols {'?uuid user-uuid}})]
    (db/transact-one conn [:db.fn/retractEntity fb-user])))

(defn facebook-connect [conn {:keys [user-uuid user-id access-token]} fb-validate-fn]
  (assert (and fb-validate-fn) "Needs a Facebook validator function.")
  (let [{:keys [user_id access_token err]} (fb-validate-fn {:user-id user-id :access-token access-token})]
    (if err
      (throw (ex-info "Facebook login error. Validating access token failed."
                      {}))
      (if-let [fb-user (db/lookup-entity (d/db conn) [:fb-user/id user_id])]
        (let [db-user (db/lookup-entity (d/db conn) [:user/uuid user-uuid])]
          (debug "Facebook user existed: " db-user " will change connected user ")
          (when-let [txs (not-empty
                           (cond-> []
                                   (not= (get-in fb-user [:fb-user/user :db/id]) (:db/id db-user))
                                   (conj [:db/add (:db/id fb-user) :fb-user/user (:db/id db-user)])
                                   ; If Facebook has returned a new access token, we need to renew that in the db as well
                                   (not= (:fb-user/token fb-user) access-token)
                                   (conj [:db/add (:db/id fb-user) :fb-user/token access_token])))]
            (debug "Updating Facebook user with transactions: " txs)
            (db/transact conn txs)))

        ; If we don't have a facebook user in the DB, check if there's an accout with a matching email.
        (do
          (debug "Creating new fb-user: " user_id)
          ;; Linking the FB user to u user account. If a user accunt with the same email exists,
          ;; it will be linked. Otherwise, a new user is created.
          (link-fb-user-to-current-account conn {:user-uuid user-uuid
                                                 :fb-user   {:fb-user/id    user_id
                                                             :fb-user/token access_token}}))))))

(defn signin
  "Create a new user and transact into datomic.

  Returns channel with username and db-after user is added to use for email verification."
  [conn email]
  {:pre [(instance? Connection conn)
         (string? email)]}
  (if email
    (let [user (db/lookup-entity (d/db conn) [:user/email email])
          email-chan (chan 1)]
      (if user
        (let [{:keys [verification/uuid] :as verification} (datomic.format/email-verification user)]
          (debug "Transacting verification for user: " (select-keys user [:db/id :user/uuid :user/email]))
          (db/transact-one conn verification)
          (info "New verification " uuid "for user:" email)
          (debug (str "Helper for mobile dev. verify uri: jourmoney://ios/1/login/verify/" uuid))
          (put! email-chan verification)
          {:email-chan email-chan
           :status (:user/status user)})
        (let [{:keys [verification] :as account} (datomic.format/user-account-map email)]
          (db/transact-map conn account)
          (debug "Creating new user with email:" email "verification:" (:verification/uuid verification))
          (put! email-chan verification)
          (debug "Done creating new user with email:" email)
          {:email-chan email-chan
           :status :user.status/new})))
    (throw (api-error ::http/unathorized :illegal-argument
                      {:type      :signup-error
                       :message   "Cannot sign in with a nil email."
                       :function  (str signin)
                       :arguments {'email email}}))))

(defn verify-email
  "Try and set the verification status if the verification with the specified uuid to :verification.status/verified.

  If more than 15 minutes has passed since the verification entity was created, sets the status to
  :verification.status/expired and throws exception.

  If the verification does not have status :verification.status/pending,
  it means that it's already verified or expired, throws exception.

  On success returns {:db/id some-id} for the user this verification belongs to."
  [conn verification-uuid]
  {:pre [(instance? Connection conn)
         (string? verification-uuid)]}
  (if-let [verification (db/lookup-entity (d/db conn) [:verification/uuid (common.format/str->uuid verification-uuid)])]
    (let [expiry-time (:verification/expires-at verification)]
      ; If the verification was not used within 15 minutes, it's expired.
      (if (or (nil? expiry-time) (>= expiry-time (c/to-long (t/now))))
        ;If verification status is not pending, we've already verified this or it's expired.
        (if (= (:verification/status verification)
                    :verification.status/pending)

          (do
            (debug "Successful verify for uuid: " (:verification/uuid verification))
            (db/transact-one conn [:db/add (:db/id verification) :verification/status :verification.status/verified])
            (:verification/entity verification))
          (throw (api-error ::http/unathorized :verification-invalid
                            {:type          :verification-error
                             :function      (str verify-email)
                             :function-args {'verification-uuid verification-uuid}
                             :message       "The verification link is invalid."})))
        (do
          (db/transact-one conn [:db/add (:db/id verification) :verification/status :verification.status/expired])
          (throw (api-error ::http/unathorized
                            :verification-expired
                            {:type          :verification-error
                             :function      (str verify-email)
                             :function-args {'verification-uuid verification-uuid}
                             :message       "The verification link is expired."})))))
    ; If verification does not exist, throw invalid error
    (throw (api-error ::http/unprocessable-entity
                      :illegal-argument
                      {:message       (str "Verification not found: " verification-uuid)
                       :function      (str verify-email)
                       :function-args {'verification-uuid verification-uuid}}))))

(declare stripe-trial)

(defn activate-account
  "Try and activate account for the user with UUId user-uuid, and user email passed in.

  Default would be the email from the user's FB account or the one they signed up with. However,
  they might change, and we need to check that it's verified. If it's verified, go ahead and activate the account.

  The user is updated with the email provided when activating the account.

  Throws exception if the email provided is not already verified, email is already in use,
  or the user UUID is not found."
  [conn user-uuid email & [opts]]
  {:pre [(instance? Connection conn)
         (string? user-uuid)
         (string? email)]}
  (if-let [user (db/lookup-entity (d/db conn) [:user/uuid (common.format/str->uuid user-uuid)])]

    (do
      (when-let [existing-user (db/lookup-entity (d/db conn) [:user/email email])]
        (if-not (= (:db/id user)
                   (:db/id existing-user))
          (throw (api-error ::http/unathorized
                            :duplicate-email
                            {:type          :authentication-error
                             :function      (str activate-account)
                             :function-args {'user-uuid user-uuid 'email email}
                             :message       (str email " is already in use. Maybe you already have an account?")}))))

      (let [user-db-id (:db/id user)
            email-changed-db (:db-after (db/transact-one conn [:db/add user-db-id :user/email email]))
            verifications (pull/verifications email-changed-db user-db-id :verification.status/verified)]

        ; There's no verification verified for this email on the user,
        ; the user is probably activating their account with a new email.
        ; Create a new verification and throw exception
        (when-not (seq verifications)
          (debug "User not verified for email:" email "will create new verification for user: " user-db-id)
          (let [user-with-new-email (db/lookup-entity (d/db conn) [:user/email email])
                verification (datomic.format/email-verification user-with-new-email)]
            (db/transact-one conn verification)
            (throw (api-error ::http/unathorized :unverified-email
                              {:type          :authentication-error
                               :function      (str activate-account)
                               :function-args {'user-uuid user-uuid 'email email}
                               :message       (str email " is not verified.")
                               :data          {:verification verification}}))))

        ; If the user is trying to activate the same account again, just return the user entity.
        (if (= (:user/status user) :user.status/active)
          (do
            (debug "User already activated, returning user.")
            (db/lookup-entity (d/db conn) [:user/email email]))
          ; First time activation, set status to active and return user entity.
          (let [activated-db (:db-after (db/transact conn [[:db/add user-db-id :user/status :user.status/active]
                                                        [:db/add user-db-id :user/currency [:currency/code "USD"]]]))
                activated-user (db/lookup-entity activated-db [:user/email email])]
            (debug "Activated account for user-uuid:" user-uuid)
            (when (:stripe-fn opts)
              (stripe-trial conn (:stripe-fn opts) activated-user))
            activated-user))))


    ; The user uuid was not found in the database.
    (throw (api-error ::http/unathorized :illegal-argument
                      {:type          :authentication-error
                       :message       (str "User with email not found: " email)
                       :function      (str activate-account)
                       :function-args {'user-uuid user-uuid 'email email}}))))

(defn share-project [conn project-uuid user-email current-user-uuid]
  (let [db (d/db conn)
        user (db/lookup-entity db [:user/email user-email])
        email-chan (chan 1)
        current-user (db/lookup-entity (d/db conn) [:user/uuid current-user-uuid])]
    (if user
      ;; If user already exists, check that they are not already sharing this project.
      (let [user-projects (db/pull db [{:project/_users [:project/uuid]}] (:db/id user))
            grouped (group-by :project/uuid (:project/_users user-projects))]
        ;; If the user is already sharing this budget, throw an exception.
        (when (get grouped project-uuid)
          (throw (api-error ::http/unprocessable-entity :duplicate-project-shares
                            {:message       (str "Project is already shared with " user-email ".")
                             :function      (str share-project)
                             :function-args {'project project-uuid 'user-email user-email}
                             :data          {:project    project-uuid
                                             :user-email user-email}})))
        ;; Else create a new verification for the user, to login through their email.
        ;; We let this verification be unlimited time, as the user invited may not see their email within 15 minutes from the invitation
        (let [verification (datomic.format/verification user)]
          (db/transact conn [verification
                          [:db/add [:project/uuid project-uuid] :project/users [:user/email user-email]]])
          (put! email-chan verification)
          {:email-chan email-chan
           :status     (:user/status user)
           :inviter    (:user/email current-user)}))

      ;; If no user exists, create a new account, and verification as normal.
      ;; And add that user to the users for the project.
      ;; TODO: We might want to create an 'invitation' entity, so that it can be pending if the user hasn't accepted to share
      (let [new-user (datomic.format/user user-email)
            verification (datomic.format/verification new-user)]
        (info "New user created: " (:user/uuid new-user))
        (db/transact conn [new-user
                        verification
                        [:db/add [:project/uuid project-uuid] :project/users (:db/id new-user)]])
        (put! email-chan verification)
        {:email-chan email-chan
         :status     (:user/status new-user)
         :inviter    (:user/email current-user)}))))

(defn delete-project
  "Delete a project entity with its transactions from the DB."
  [conn project-dbid]
  ; Find transactions that are referring to the project to be deleted. We need to make sure these are also deleted from the DB
  (let [transactions (db/all-with (d/db conn) {:where   '[[?e :transaction/project ?p]]
                                              :symbols {'?p project-dbid}})
        delete-transactions-txs (map (fn [dbid]
                                       [:db.fn/retractEntity dbid])
                                     transactions)]
    (db/transact conn (conj
                              delete-transactions-txs
                              [:db.fn/retractEntity project-dbid]))))

;(defn stripe-subscribe
;  "Subscribe user to a plan in Stripe. Basically create a Stripe customer for this user and subscribe to a plan."
;  [conn stripe-fn {:keys [stripe/customer
;                          stripe/subscription]} {:keys [token plan] :as p}]
;  {:pre [(instance? Connection conn) (fn? stripe-fn) (map? p)]}
;  (let [{:keys [id email quantity]} token
;        params {"source"    id
;                "plan"      plan
;                "trial_end" "now"
;                "quantity" 0}]
;    (if (some? customer)
;      (if-let [subscription-id (:stripe.subscription/id subscription)]
;        ;; We have a subscription saved in datomic, so just update that.
;        (let [updated (stripe-fn :subscription/update
;                                 {:customer-id     customer
;                                  :subscription-id subscription-id
;                                  :params          params})]
;          (transact conn [[:db/add [:stripe.subscription/id subscription-id] :stripe.subscription/status (:stripe.subscription/status updated)]
;                          [:db/add [:stripe.subscription/id subscription-id] :stripe.subscription/period-end (:stripe.subscription/period-end updated)]]))
;
;        ;; We don't have a subscription saved for the customer (maybe the user canceled at some point). Create a new one.
;        (let [created (stripe-fn :subscription/create
;                                 {:customer-id customer
;                                  :params      params})
;              db-subscription (assoc created :db/id (d/tempid :db.part/user))]
;          (transact conn [db-subscription
;                          [:db/add [:stripe/customer customer] :stripe/subscription (:db/id db-subscription)]])))
;
;      ;; We don't have a Stripe customer, so we need to create it.
;      (let [{user-id :db/id} (pull/pull (d/db conn) [:db/id] [:user/email email])
;            stripe (stripe-fn :customer/create
;                              {:params (assoc params "email" email)})
;            account (datomic.format/stripe-account user-id stripe)]
;        (assert (some? user-id))
;        (transact-one conn account)))))

(defn stripe-update-subscription
  [_ stripe-fn {:keys [stripe/customer
                          stripe/subscription]} {:keys [quantity]}]
  (when (some? customer)
    (let [updated (stripe-fn :subscription/update
                             {:customer-id customer
                              :subscription-id (:stripe.subscription/id subscription)
                              :params {"quantity" quantity}})]
      (debug "Updated customer subscription: " updated))))
(defn stripe-update-card
  [conn stripe-fn {:keys [stripe/customer
                          stripe/subscription]} {:keys [token]}]
  (let [{:keys [id email]} token
        subscription-id (:stripe.subscription/id subscription)]
    (if (some? customer)
      (let [updated-customer (stripe-fn :customer/update
                               {:customer-id customer
                                :params      {"source" id}})
            update-subscription (stripe-fn :subscription/update
                                           {:customer-id customer
                                            :subscription-id subscription-id
                                            :params {"trial_end" "now"}})]
        (debug "Did update stripe customer: " updated-customer)
        (debug "Updated subscription: " update-subscription)
        (db/transact-one conn [:db/add [:stripe.subscription/id subscription-id] :stripe.subscription/status (:stripe.subscription/status update-subscription)]))
      (throw (ex-info (str "Could not find customer for user with email: " email)
                      {:data {:token token}})))))

(defn stripe-delete-card
  [_ stripe-fn {:keys [stripe/customer
                       stripe/subscription]} {:keys [card] :as p}]
  (if (some? customer)
    (let [deleted (stripe-fn :card/delete
                             {:customer-id customer
                              :subscription-id (:stripe.subscription/id subscription)
                              :card card})
          updated-sub (stripe-fn :subscription/update
                                 {:customer-id customer
                                  :subscription-id (:stripe.subscription/id subscription)
                                  :params {"quantity" 0}})]
      (debug "Deleted card from customer: " customer " response: " deleted)
      (debug "Updated subscription: " updated-sub)
      updated-sub)
    (throw (ex-info (str "Could not find customer")
                    {:data p}))))

(defn stripe-trial
  "Subscribe user to trial, without requesting credit carg."
  [conn stripe-fn {:keys [user/email stripe/_user] :as user}]
  {:pre [(instance? Connection conn)
         (fn? stripe-fn)
         (string? email)]}
  (when (seq _user)
    (throw (api-error ::http/unprocessable-entity :illegal-argument
                      {:message       "Cannot start a trial for user that is already a Stripe customer."
                       :function      (str stripe-trial)
                       :function-args {'stripe-fn stripe-fn
                                       'user      user}})))
  (let [{user-id :db/id} (db/pull (d/db conn) [:db/id] [:user/email email])
        stripe (stripe-fn :customer/create
                          {:params {"plan"  "paywhatyouwant"
                                    "email" email
                                    "quantity" 0}})]
    (debug "Starting trial for user: " email " with stripe info: " stripe)
    (assert (some? user-id))
    (when stripe
      (db/transact-one conn (datomic.format/stripe-account user-id stripe)))))

;(defn stripe-cancel
;  "Cancel the subscription in Stripe for user with uuid."
;  [{:keys [state stripe-fn]} stripe-account]
;  {:pre [(instance? Connection state)
;         (fn? stripe-fn)
;         (map? stripe-account)]}
;  ;; If no customer-id exists, we cannot cancel anything.
;  (when-not (:stripe/customer stripe-account)
;    (throw (api-error ::http/unprocessable-entity :missing-required-fields
;                      {:message       "Required fields are missing. Cannot transact entities."
;                       :missing-keys  [:stripe/customer]
;                       :function      (str stripe-cancel)
;                       :function-args {'stripe-fn stripe-fn 'stripe-account stripe-account}})))
;  ;; If we have a subscription id in the db, try and cancel that
;  (if-let [subscription-id (get-in stripe-account [:stripe/subscription :stripe.subscription/id])]
;    ; Find the stripe account for the user.
;    (let [subscription (stripe-fn :subscription/cancel
;                                     {:customer-id     (:stripe/customer stripe-account)
;                                      :subscription-id subscription-id})]
;      (transact/transact state [[:db.fn/retractEntity [:stripe.subscription/id (:stripe.subscription/id subscription)]]]))
;    ;; We don't have a subscription ID so we cannot cancel.
;    (throw (api-error ::http/unprocessable-entity :missing-required-fields
;                      {:message       "Required fields are missing. Cannot transact entities."
;                       :missing-keys  [:stripe.subscription/id]
;                       :function      (str stripe-cancel)
;                       :function-args {'stripe-fn stripe-fn 'stripe-account stripe-account}}))))

(defn newsletter-subscribe [conn email]
  (let [{:keys [verification] :as account} (datomic.format/user-account-map email)]
    (mailchimp/subscribe (env :mail-chimp-api-key)
                         (env :mail-chimp-list-id)
                         email
                         (:verification/uuid verification))
    (info "Newsletter subscribe successful, transacting user into datomic.")
    (comment
      ;; TODO: Actually transact this if we want to release jourmoney ^^
      (transact-map conn account))))

