(ns metabase.automagic-dashboards.populate
  "Create and save models that make up automagic dashboards."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase.api
             [common :as api]
             [card :as card.api]]
            [metabase.events :as events]
            [metabase.models.dashboard :as dashboard]
            [toucan
             [db :as db]
             [hydrate :refer [hydrate]]]))

(def ^Integer grid-width
  "Total grid width."
  18)
(def ^Integer default-card-width
  "Default card width."
  6)
(def ^Integer default-card-height
  "Default card height"
  4)

(defn- create-collection!
  [title color description]
  (when api/*is-superuser?*
    (db/insert! 'Collection
      :name        title
      :color       color
      :description description)))

(def ^:private automagic-collection
  "Get or create collection used to store all autogenerated cards.
   Value is wrapped in a delay so that we don't hit the DB out of order."
  (delay
   (or (db/select-one 'Collection
                      :name "Automatically Generated Questions")
       (create-collection! "Automatically Generated Questions"
                           "#000000"
                           "Cards used in automatically generated dashboards."))))

(defn- create-card!
  [{:keys [visualization title description query]}]
  (let [[display visualization-settings] visualization
        card (db/insert! 'Card
               :creator_id             api/*current-user-id*
               :dataset_query          query
               :description            description
               :display                display
               :name                   title
               :visualization_settings visualization-settings
               :result_metadata        (card.api/result-metadata-for-query query)
               :collection_id          (-> automagic-collection deref :id))]
    (events/publish-event! :card-create card)
    (hydrate card :creator :dashboard_count :labels :can_write :collection)
    card))

(defn- add-card!
  [dashboard card [x y]]
  (dashboard/add-dashcard! dashboard (create-card! card)
    {:col   y
     :row   x
     :sizeX (:width card)
     :sizeY (:height card)}))

(defn- add-text-card!
  [dashboard {:keys [text width height]} [x y]]
  (dashboard/add-dashcard! dashboard nil
    {:creator_id             api/*current-user-id*
     :visualization_settings {:text text
                              :virtual_card {:name                   nil
                                             :display                :text
                                             :dataset_query          nil
                                             :visualization_settings {}}}
     :col   y
     :row   x
     :sizeX width
     :sizeY height}))

(def ^:private ^Integer max-cards 9)

(defn- make-grid
  [width height]
  (vec (repeat height (vec (repeat width false)))))

(defn- fill-grid
  "Mark a rectangular area starting at [`x`, `y`] of size [`width`, `height`] as
   occupied."
  [grid [x y] {:keys [width height]}]
  (reduce (fn [grid xy]
            (assoc-in grid xy true))
          grid
          (for [x (range x (+ x height))
                y (range y (+ y width))]
            [x y])))

(defn- accomodates?
  "Can we place card on grid starting at [x y] (top left corner)?
   Since we are filling the grid top to bottom and the cards are rectangulard,
   it suffices to check just the first (top) row."
  [grid [x y] {:keys [width height]}]
  (and (<= (+ x height) (count grid))
       (<= (+ y width) (-> grid first count))
       (every? false? (subvec (grid x) y (+ y width)))))

(defn- card-position
  "Find position on the grid where to put the card.
   We use the dumbest possible algorithm (the grid size is relatively small, so
   we should be fine): startting at top left move along the grid from left to
   right, row by row and try to place the card at each position until we find an
   unoccupied area. Mark the area as occupied."
  [grid start-row card]
  (reduce (fn [grid xy]
            (if (accomodates? grid xy card)
              (reduced xy)
              grid))
          grid
          (for [x (range start-row (count grid))
                y (range (count (first grid)))]
            [x y])))

(defn- bottom-row
  "Find the bottom of the grid. Bottom is the first completely empty row with
   another empty row below it."
  [grid]
  (let [row {:height 0 :width grid-width}]
    (loop [bottom 0]
      (let [[bottom _]      (card-position grid bottom row)
            [next-bottom _] (card-position grid (inc bottom) row)]
        (if (= (inc bottom) next-bottom)
          bottom
          (recur next-bottom))))))

(defn- add-group!
  [dashboard grid group cards]
  (let [start-row (bottom-row grid)
        start-row (cond-> start-row
                    ;; First row doesn't need empty space above
                    (pos? start-row) inc
                    group            (+ 2))]
    (reduce (fn [grid card]
              (let [xy (card-position grid start-row card)]
                (add-card! dashboard card xy)
                (fill-grid grid xy card)))
            (if group
              (let [xy   [(- start-row 2) 0]
                    card {:text   (format "# %s" (:title group))
                          :width  default-card-width
                          :height 2}]
                (add-text-card! dashboard card xy)
                (fill-grid grid xy card))
              grid)
            cards)))

(defn- shown-cards
  "Pick up to `max-cards` with the highest `:score`.
   Keep groups together if possible by pulling all the cards within together and
   using the same (highest) score for all.
   Among cards with the same score those beloning to the largest group are
   favourized, but it is still possible that not all cards in a group make it
   (consider a group of 4 cards which starts as 7/9; in that case only 2 cards
   from the group will be picked)."
  [cards]
  (->> cards
       (group-by (some-fn :group hash))
       (map (fn [[_ group]]
              (let [group-position (apply max (map :position group))]
                ;; Fractional positioning to keep in-group ordering and position
                ;; the group in the right spot.
                {:cards (map #(update % :position (fn [position]
                                                    (->> position
                                                         inc
                                                         /
                                                         (- 1)
                                                         (+ group-position))))
                             group)
                 :score (apply max (map :score group))
                 :size  (count group)})))
       (sort-by (juxt :score :size) (comp (partial * -1) compare))
       (mapcat :cards)
       (take max-cards)))

(defn create-dashboard!
  "Create dashboard and populate it with cards."
  [{:keys [title description groups]} cards]
  (let [dashboard (db/insert! 'Dashboard
                    :name        title
                    :description description
                    :creator_id  api/*current-user-id*
                    :parameters  [])
        cards     (sort-by :position (shown-cards cards))
        ;; Binding return value to make linter happy
        _         (try
                    (->> cards
                            (partition-by :group)
                            (reduce (fn [grid cards]
                                      (let [group (some-> cards first :group groups)]
                                        (add-group! dashboard grid group cards)))
                                    ;; Height doesn't need to be precise, just some
                                    ;; safe upper bound.
                                    (make-grid grid-width (* max-cards grid-width))))
                    (catch Exception e (log/error [e groups])))]
    (events/publish-event! :dashboard-create dashboard)
    (log/info (format "Adding %s cards to dashboard %s:\n%s"
                      (count cards)
                      (:id dashboard)
                      (str/join "; " (map :title cards))))
    dashboard))
