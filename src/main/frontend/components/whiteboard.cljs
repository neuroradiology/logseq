(ns frontend.components.whiteboard
  (:require [cljs.math :as math]
            [datascript.core :as d]
            [frontend.components.page :as page]
            [frontend.components.reference :as reference]
            [frontend.db.model :as model]
            [frontend.handler.route :as route-handler]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]
            [shadow.loader :as loader]))

(defonce tldraw-loaded? (atom false))
(rum/defc tldraw-app < rum/reactive
  {:init (fn [state]
           (p/let [_ (loader/load :tldraw)]
             (reset! tldraw-loaded? true))
           state)}
  [name shape-id]
  (let [loaded? (rum/react tldraw-loaded?)
        draw-component (when loaded?
                         (resolve 'frontend.extensions.tldraw/tldraw-app))]
    (when draw-component
      (draw-component name shape-id))))

;; TODO: make it reactive to db changes
(rum/defc tldraw-preview < rum/reactive
  {:init (fn [state]
           (p/let [_ (loader/load :tldraw)]
             (reset! tldraw-loaded? true))
           state)}
  [page-name]
  (let [loaded? (rum/react tldraw-loaded?)
        tldr (whiteboard-handler/page-name->tldr! page-name)
        generate-preview (when loaded?
                           (resolve 'frontend.extensions.tldraw/generate-preview))]
    (when generate-preview
      (generate-preview tldr))))

(rum/defc page-refs-count < rum/static
  ([page-name classname]
   (page-refs-count page-name classname nil))
  ([page-name classname children]
   (let [page-entity (model/get-page page-name)
         block-uuid (:block/uuid page-entity)
         refs-count (count (:block/_refs page-entity))
         [open? set-open?] (rum/use-state nil)]
     (when (> refs-count 0)
       (ui/tippy {:in-editor?      false
                  :html            (fn [] [:div.mx-2 (reference/block-linked-references block-uuid)])
                  :interactive     true
                  :delay           [100, 500]
                  :position        "bottom"
                  :distance        10
                  :open?           open?
                  :popperOptions   {:modifiers {:preventOverflow
                                                {:enabled           true
                                                 :boundariesElement "viewport"}}}}
                 [:div.flex.items-center.gap-2.whiteboard-page-refs-count
                  {:class classname
                   :on-click (fn [e]
                               (util/stop e)
                               (set-open? (fn [o] (if (nil? o) true nil))))}
                  [:div.open-page-ref-link refs-count]
                  children])))))

(defn- get-page-display-name
  [page-name]
  (let [page-entity (model/get-page page-name)]
    (or (get-in page-entity [:block/properties :title] nil)
        (:block/original-name page-entity)
        page-name)))

;; This is not accurate yet
;; (defn- get-page-human-update-time
;;   [page-name]
;;   (let [page-entity (model/get-page page-name)
;;         updated-at (:block/updated-at page-entity)]
;;     (str "Edited at " (util/time-ago (js/Date. updated-at)))))

(rum/defc dashboard-preview-card
  [page-name]
  [:div.dashboard-card.dashboard-preview-card.cursor-pointer.hover:shadow-lg
   {:on-click
    (fn [e]
      (util/stop e)
      (route-handler/redirect-to-whiteboard! page-name))}
   [:div.dashboard-card-title
    [:div.flex.w-full
     [:div.dashboard-card-title-name (get-page-display-name page-name)]
     [:div.flex-1]
     (page-refs-count page-name nil)]
    ;; [:div.flex.w-full
    ;;  [:div (get-page-human-update-time page-name)]
    ;;  [:div.flex-1]
    ;;  (page-refs-count page-name)]
    ]
   [:div.p-4.h-64.flex.justify-center
    (tldraw-preview page-name)]])

(rum/defc dashboard-create-card
  []
  [:div.dashboard-card.dashboard-create-card.cursor-pointer
   {:on-click
    (fn [e]
      (util/stop e)
      (route-handler/redirect-to-whiteboard! (d/squuid)))}
   (ui/icon "plus")
   [:span.dashboard-create-card-caption
    "New whiteboard"]])

;; TODO: move it to util?
(defn- use-component-size
  [ref]
  (let [[rect set-rect] (rum/use-state nil)]
    (rum/use-effect!
     (fn []
       (let [update-rect #(set-rect (when (.-current ref) (.. ref -current getBoundingClientRect)))
             updator (fn [entries]
                       (when (.-contentRect (first (js->clj entries))) (update-rect)))
             observer (js/ResizeObserver. updator)]
         (update-rect)
         (.observe observer (.-current ref))
         #(.disconnect observer)))
     [])
    rect))

(rum/defc whiteboard-dashboard
  []
  (let [whiteboards (model/get-all-whiteboards (state/get-current-repo))
        whiteboard-names (map :block/name whiteboards)
        ref (rum/use-ref nil)
        rect (use-component-size ref)
        [container-width] (when rect [(.-width rect) (.-height rect)])
        cols (cond (< container-width 600) 1
                   (< container-width 900) 2
                   (< container-width 1200) 3
                   :else 4)
        total-whiteboards (count whiteboards)
        empty-cards (- (max (* (math/ceil (/ (inc total-whiteboards) cols)) cols) (* 2 cols))
                       (inc total-whiteboards))]
    [:<>
     [:h1.title
      "All whiteboards"
      [:span.opacity-50
       (str " · " total-whiteboards)]]
     [:div
      {:ref ref}
      [:div.gap-8.grid.grid-rows-auto
       {:style {:grid-template-columns (str "repeat(" cols ", minmax(0, 1fr))")}}
       (dashboard-create-card)
       (for [whiteboard-name whiteboard-names]
         [:<> {:key whiteboard-name} (dashboard-preview-card whiteboard-name)])
       (for [n (range empty-cards)]
         [:div.dashboard-card.dashboard-bg-card {:key n}])]]]))

(rum/defc whiteboard-page
  [name block-id]
  [:div.absolute.w-full.h-full.whiteboard-page

   ;; makes sure the whiteboard will not cover the borders
   {:key name
    :style {:padding "0.5px" :z-index 0
            :transform "translateZ(0)"
            :text-rendering "geometricPrecision"
            :-webkit-font-smoothing "subpixel-antialiased"}}

   [:div.whiteboard-page-title-root
    [:span.whiteboard-page-title
     {:style {:color "var(--ls-primary-text-color)"}}
     (page/page-title name [:span.tie.tie-whiteboard
                            {:style {:font-size "0.9em"}}]
                      name nil false)]

     (page-refs-count name
                      "text-md px-3 py-1 cursor-default whiteboard-page-refs-count"
                      [:<> "Reference" (ui/icon "references-show")])]
   (tldraw-app name block-id)])

(rum/defc whiteboard-route
  [route-match]
  (let [name (get-in route-match [:parameters :path :name])
        {:keys [block-id]} (get-in route-match [:parameters :query])]
    (whiteboard-page name block-id)))
