(ns eponai.common.ui.index
  (:require
    [eponai.common.ui.common :as common]
    [eponai.common.ui.navbar :as nav]
    [eponai.common.ui.elements.photo :as photo]
    [eponai.web.ui.photo :as p]
    [eponai.common.ui.product-item :as pi]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [taoensso.timbre :refer [debug error]]
    [eponai.common.ui.dom :as my-dom :refer [div a]]
    [eponai.common.ui.elements.css :as css]
    [eponai.client.routes :as routes]
    [eponai.common.ui.icons :as icons]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.common.ui.router :as router]))

(defn top-feature [opts icon title text]
  (dom/div #js {:className "feature-item column"}
    (div
      (->> (css/grid-row)
           (css/add-class :align-middle))
      (div (->> (css/grid-column)
                (css/grid-column-size {:small 2 :medium 12}))
           icon)
      (div (css/grid-column)
           (dom/strong #js {:className "feature-title"} title)
           (dom/p nil text)))))

(defn banner [{:keys [color align] :as opts} primary secondary]
  (let [align (or align :left)
        color (or color :default)]
    (dom/div #js {:className (str "banner " (name color))}
      (grid/row
        nil
        (grid/column
          (cond->> (->> (grid/column-size {:small 9 :medium 8})
                        (css/text-align align))
                   (= align :right)
                   (grid/column-offset {:small 3 :medium 4}))
          primary)
        (grid/column
          (css/align :right)
          secondary)))))

(defn collection-element [{:keys [href url title full? url-small photo-id]}]
  ;; Use the whole thing as hover elem
  (my-dom/a
    {:href    href
     :classes [:full :category-photo]}
    (p/photo {:photo-id photo-id}
             (p/overlay
               nil(my-dom/div
                          (->> (css/text-align :center))
                          (dom/span nil title))))))

(defui Index
  static om/IQuery
  (query [_]
    [{:proxy/navbar (om/get-query nav/Navbar)}
     {:query/featured-items [:db/id
                             :store.item/name
                             :store.item/price
                             {:store.item/photos [{:store.item.photo/photo [:photo/path :photo/id]}
                                                  :store.item.photo/index]}
                             {:store/_items [{:store/profile [:store.profile/name]}]}]}
     {:query/featured-stores [:db/id
                              {:store/profile [:store.profile/name
                                               {:store.profile/photo [:photo/path :photo/id]}]}
                              :store/featured
                              :store/featured-img-src
                              {:store/items [:db/id {:store.item/photos [{:store.item.photo/photo [:photo/path :photo/id]}
                                                                         :store.item.photo/index]}]}]}
     {:query/featured-streams [:db/id :stream/title {:stream/store [:db/id {:store/profile [:store.profile/name {:store.profile/photo [:photo/path :photo/id]}]}]}]}])
  Object
  (render [this]
    (let [{:keys [proxy/navbar query/featured-items query/featured-streams]} (om/props this)
          {:keys [input-search]} (om/get-state this)]
      (debug "Featured items: " featured-items)
      (dom/div #js {:id "sulo-index" :className "sulo-page"}
        (common/page-container
          {:navbar navbar}
          (dom/div #js {:id "sulo-index-container" :onScroll #(debug "Did scroll page: " %)}

            (p/header
              (css/add-class :center {:photo-id "static/home-header-bg"})
              (div
                (->> (css/grid-row)
                     (css/add-class :intro-header)
                     (css/add-class :align-middle))

                (div
                  (->>
                    (css/grid-column)
                    (css/add-class :header-content)
                    (css/text-align :center)
                    (css/grid-column-offset {:large 5 :medium 2}))
                  (div
                    (css/text-align :left)
                    (dom/h1 #js {:id "header-content" :className "show-for-sr"} "SULO")
                    ;(dom/h1 nil "LO" (dom/small nil "CAL"))
                    (dom/h2 #js {:className "header"} "Your local marketplace online")
                    (dom/p nil (dom/strong nil (dom/i #js {:className "fa fa-map-marker fa-fw"}) "Vancouver, BC")))

                  (div (->> (css/grid-row)
                            (css/add-class :search-container))
                       (div (->> (css/grid-column)
                                 (css/grid-column-size {:small 12 :medium 8}))
                            (dom/input #js {:className   "drop-shadow"
                                            :placeholder "What are you looking for?"
                                            :type        "text"
                                            :value       (or input-search "")
                                            :onChange    #(do (debug " search " (.. % -target -value)) (om/update-state! this assoc :input-search (.. % -target -value)))
                                            :onKeyDown   (fn [e]
                                                           #?(:cljs
                                                              (when (= 13 (.. e -keyCode))
                                                                (let [search-string (.. e -target -value)]
                                                                  (set! js/window.location (str "/products?search=" search-string))))))}))
                       (div (->> (css/grid-column)
                                 (css/grid-column-size {:small 4 :medium 3})
                                 (css/text-align :left))
                            (dom/a #js {:className "button expanded search drop-shadow"
                                        :onClick   (fn []
                                                     #?(:cljs
                                                        (set! js/window.location (str "/products?search=" input-search))))}
                                   "Search"))
                       )
                  )))

            (dom/div #js {:className "top-features"}
              (dom/div #js {:className " row small-up-1 medium-up-3"}
                (top-feature
                  nil
                  (icons/shopping-bag)
                  "Shop and Discover"
                  "Get lost in a marketplace filled with your local gems.")
                (top-feature
                  nil
                  (icons/video-camera)
                  "Watch, chat and follow"
                  "Hang out with your favourite local brands on SULO LIVE.")
                (top-feature
                  nil
                  (icons/heart)
                  "Join the Community"
                  "Sign up to follow others and share your faves.")))


            (common/content-section {:href  (routes/url :live)
                                     :class "online-channels"}
                                    "Stores streaming right now"
                                    (grid/row
                                      (css/add-class :collapse)
                                      (grid/column
                                        (css/add-class :online-streams)
                                        (map (fn [c]
                                               (my-dom/div
                                                 (css/add-class :online-stream)
                                                 (common/online-channel-element c)))
                                             featured-streams)))
                                    "See More")

            (common/content-section {:class "collections"}
                                    "Shop by collection"
                                    (div nil
                                         (grid/row
                                           (grid/columns-in-row {:small 1 :medium 2})
                                           (grid/column
                                             (->> (css/add-class :content-item)
                                                  (css/add-class :collection-item))
                                             (collection-element {:href     (routes/url :browse/category {:top-category "home"})
                                                                  :photo-id "static/home"
                                                                  :title    "Home"}))
                                           (grid/column
                                             (->> (css/add-class :content-item)
                                                  (css/add-class :collection-item))
                                             (collection-element {:href     (routes/url :browse/gender {:sub-category "women"})
                                                                  :photo-id "static/women"
                                                                  :title    "Women"}))
                                           (grid/column
                                             (->> (css/add-class :content-item)
                                                  (css/add-class :collection-item))
                                             (collection-element {:href      (routes/url :browse/gender {:sub-category "men"})
                                                                  :photo-id  "static/men"
                                                                  :title     "Men"}))
                                           (grid/column
                                             (->> (css/add-class :content-item)
                                                  (css/add-class :collection-item))
                                             (collection-element {:href     (routes/url :browse/gender {:sub-category "unisex-kids"})
                                                                  :photo-id "static/kids"
                                                                  :title    "Kids"}))))
                                    ;(map (fn [s t]
                                    ;       (collection-element {:url (first (:store/featured-img-src s))
                                    ;                            :title t}))
                                    ;     featured-stores
                                    ;     ["Home" "Kids" "Women" "Men"])
                                    ""
                                    )

            (common/content-section {:href  (routes/url :browse/all-items)
                                     :class "new-arrivals"}
                                    "New arrivals"
                                    (grid/row
                                      (css/add-class :collapse)
                                      (grid/column
                                        (css/add-class :new-arrivals-container)
                                        (map
                                          (fn [p]
                                            (my-dom/div
                                              (css/add-class :new-arrival-item)
                                              (pi/product-element {:open-url? true} p)))
                                          (take 4 featured-items))))
                                    "See More")

            (banner {:color :default}
                    (dom/div nil
                      (dom/h2 nil "Watch, shop and chat with your favorite vendors and artisans.")
                      (dom/p nil "Follow and stay up-to-date on when they're online to meet you!")
                      (dom/a #js {:className "button"} "Join"))
                    (icons/heart-drawing))

            (banner {:color :white
                     :align :right}
                    (dom/div nil
                      (dom/h2 nil "Open your own shop on SULO and tell your story to Vancouver.")
                      (dom/p nil "Enjoy a community that lives for local.")
                      (dom/a #js {:className "button gray hollow"} "Contact Us"))
                    nil)))))))


(def ->Index (om/factory Index))

(router/register-component :index Index)