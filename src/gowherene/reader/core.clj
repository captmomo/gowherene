(ns gowherene.reader.core
  (:require [hickory.core :refer [as-hickory parse]]
            [hickory.zip :refer [hickory-zip]]
            [clojure.zip :as zip]
            [clojure.string :as str]
            [hickory.select :as s]
            [medley.core :refer [take-upto distinct-by]]
            [gowherene.reader.tagger :as tagger]
            [gowherene.reader.geocoding :refer [geocode add-latlng]]
            [gowherene.reader.regexes :refer [re-postal-code re-address, re-spaces]]
            [gowherene.reader.geocodables :refer [geocodables]]
            [gowherene.reader.location :refer [add-location]]
            [gowherene.reader.label :refer [add-label]]))

(defn hickory-zipper
  [page]
  (hickory-zip (as-hickory (parse page))))

(def ^:private unimportant-tags
  "These are tags that don't carry information relating to addresses, or are tags we don't need.

  Here are some tags that you think should not carry information, but are actually useful

  <br>: It is sometimes used to indicate the end of an address
        (the text after the <br> might be a phone number, for example
  "
  #{:meta :noscript :script :link :style :header :footer :head :nav :img
    :progress :ins :iframe})

(defn- remove-nodes
  "Remove any node where (pred node) is true from zipper before returning the zipper.
  Returns a hickory-zip.

  Assumes that zipper is at the root, and that this zipper was built from a hickory."
  [pred zipper]
  (->> zipper
       (iterate (fn [loc]
                  (if (pred (zip/node loc))
                    (zip/remove loc)
                    (zip/next loc))))
       (take-while (complement zip/end?))
       last
       zip/root
       hickory-zip))

(defn cleanup
  [page-zipper]
  (remove-nodes
   #(or
     (unimportant-tags (:tag %))
     ;; Remove nodes with nil content
     (and (map? %)
          ;; Be defensive, since some tags, such as <hr> and <br> might be useful information
          (#{:a :div :span :i} (:tag %))
          (nil? (:content %)))
     ;; Remove all nodes that are just spaces (keep \n and \t because they might be useful information
     (and (string? %) (= "" (str/replace % re-spaces "")))
     ;; Actually comments could be useful if, perhaps, they provide labels like title or address
     (= :comment (:type %)))
   page-zipper))

(defn publish
  [data]
  (->> data
       (filter #(:latlng %))
       (map #(select-keys % [:label :location :latlng]))))

(defn process-clean-zipper
  [zipper]
  (->> zipper
       geocodables
       ;; list of geocodables. for each geocodable found
       ;; {:type :postal-code :value <postal-code> :loc <loc in zipper>}
       ;; {:type :address :value <address> :loc <loc in zipper>}
       (map add-location)
       ;; {:type :address :value <address> :loc <loc in zipper>
       ;;  :location <as much location info as possible, usually the full address}
       (map add-label)
       ;; {:type :address :value <address> :loc <loc in zipper>
       ;;  :location <as much location info as possible, usually the full address
       ;;  :location-label <name of shop, event, etc>}
       ))

(defn process
  [page]
  (->> page
       hickory-zipper
       cleanup
       process-clean-zipper

       ;; do deduplication

       (pmap add-latlng)
       publish))

(comment
  (def address-cap
    "We use (s/has-child (s/has-child (s/find-in-text re-address)))
     to match the `Address: ` label followed by the actual address.
     Since re-address could potentially match stray `address` words in text,
     we cap this search at address-cap.
  <blah>
    <blah0>
      <blah>Address: </blah>
    </blah0>
    <blah1 />
    <blah... We don't want too many of these here./>
    <blah... We don't want too many of these here./>
    <blah.address-cap+1 />
  </blah>"
    20)

  (defn address-label-like
    [hzip-loc]
    (some #(and
            ;; Matches re-address
            (re-find re-address %)
            ;; Does not have too many words (not likely to be labels)
            (< (tagger/count-words %) 4))
          (->> (zip/node hzip-loc)
               :content
               (filter string?))))

  ;; TODO rename this function
  (defn get-postal-code-locs
    "Given a hickory, find all the locs containing postal codes"
    [hickory]
    (let [;; Contains postal code
          locs-postal-code (s/select-locs
                            (s/find-in-text re-postal-code) hickory)
          ;; Find regions of content near labels like "Address"
          locs-address (s/select-locs
                        (s/or (s/has-child (s/has-child address-label-like))
                              (s/has-child address-label-like)) hickory)
          locs-address-filtered (filter #(> address-cap ((comp count :content zip/node) %))
                                        locs-address)]
      (clojure.set/union (set locs-postal-code) (set locs-address-filtered))))

  (defn get-earlier-header
    "Given a loc, find the header just above or before this loc.
  Limit the search backwards to earlier-header-steps"
    [hloc-zip]
    (s/prev-pred hloc-zip
                 (apply s/or (map s/tag [:h1 :h2 :h3 :h4]))))

  (defn get-content
    "Given a node, return all content in a string, until the first <br>
     or the end of this tree of tags
   The aux function returns a pair (string should-stop) where string
     is the data to be accumulated and should-stop stops execution if necessary"
    [node]
    (first ((fn aux [n]
              (cond (= (:tag n) :br) (list "" true)
                    (= (:type n) :element)
                    (let [useful (take-upto second (map aux (:content n)))]
                      (list (str/join (map first useful))
                            (some second useful)))
                    :else (list n false))) node)))

  (defn loc->addresses
    [loc]
    (let [addresses (->> loc
                         tagger/loc->buckets
                         tagger/buckets->addresses)]
      (if (seq addresses)
        ;; If addresses is not empty
        (let [max-address-value (second (apply max-key second addresses))]
          (->> addresses
               (filter #(= max-address-value (second %)))
               (map first)))
        addresses)))

  (defn update-if-exists
    [map key f]
    (if (key map)
      (update map key f)
      map))

  (defn simplify-datum
    "Use this to reduce the verbosity of datum (good for pprinting)"
    ([datum] (simplify-datum 0 datum))
    ([verbosity datum]
     (let [locs [:postal-code-loc :header-loc]]
       (cond
         (> verbosity 1) datum
         (> verbosity 0) (reduce #(update-if-exists %1 %2 zip/node) datum locs)
         :else (reduce #(update-if-exists %1 %2 tagger/count-locs-walked) datum locs)))))

  (defn tag-with
    [tag info & datum]
    (assoc datum tag info))

  (defn update-with-tag
    "Given an old tag in a map m,
     get the value for the old tag in m,
     apply f on it,
     associate the new value back into m with key new-tag."
    [new-tag old-tag f m]
    (let [old-info (old-tag m)
          new-info (f old-info)]
      (assoc m new-tag new-info)))

  (defn update-with-tag-seq
    "Given an old tag in a map m,
     get the value for the old tag in m,
     apply f on it, (f returns a seq)
     clone m and
     associate the new value back into m's clones with key new-tag."
    [new-tag old-tag f m]
    (let [old-info (old-tag m)
          new-info (f old-info)]
      (map (partial assoc m new-tag) new-info)))

  (defn loc->place
    [loc]
    (-> loc
        zip/node
        get-content
        (str/replace re-spaces " ")
        str/trim))

  (defn gather-address-info
    "Takes a hickory and returns a data of all the places and addresses on the page"
    [hickory]
    (->> hickory
         (remove-tags uninteresting-tags)
         get-postal-code-locs
         (map (partial tag-with :postal-code-loc))
         (map (partial update-with-tag :header-loc :postal-code-loc get-earlier-header))
         ;; If we can't find the header, don't display it
         ;; (filter :header-loc)
         (map (partial update-with-tag :place :header-loc loc->place))
         ;; Uncomment the following two for debugging
         ;; (map (partial update-with-tag :buckets :postal-code-loc tagger/loc->buckets))
         ;; (map (partial update-with-tag :addresses :buckets tagger/buckets->addresses))
         (mapcat (partial update-with-tag-seq :address :postal-code-loc loc->addresses))
         ;; Some postal-code-locs are misidentified, hence addresses cannot be found
         (filter :address)))

  (defn get-index [header]
    (and header
         (if-let [num (re-find #"(\d+)\." header)]
           (Integer/parseInt (get num 1))
           nil)))

  (defn publish-old
    [data]
    (->> data
         (filter #(:latlng %))
         (map #(select-keys % [:place :address :latlng]))))

  (defn retain-longer-names
    "Takes a fragment of data, already sorted in order of place length"
    ([data-fragment]
     (retain-longer-names data-fragment []))
    ([[d & ds] accum]
     (if-let [place (:place d)]
       (if (some #(and (str/starts-with? % place) (not= place %))
                 (map :place ds))
         ;; There exists longer versions of this place's name
         (recur ds accum)
         (recur ds (conj accum d)))
       accum)))

  (defn- dedupe-data-retain-longer-names
    "Given data, this function removes the location with the shorter name
  if two or more locations have identical addresses"
    [data]
    (let [groups (group-by :address data)
          partitions (group-by (fn [[a g]] (> (count g) 1)) groups)
          uniques (map #(get-in % [1 0]) (partitions false))

          longer-names (->> (partitions true)
                            (map second)
                            (map (partial sort-by (comp count :place)))
                            (mapcat retain-longer-names))]
      (lazy-cat uniques longer-names)))

  (defn data-add-geocoding
    "Adds geocoding to data
  Tries to minimize the number of geocoding requests by doing necessary deduplication first"
    [data]
    (->> data
         (distinct-by (fn [d] [(:place d) (:address d)]))
         dedupe-data-retain-longer-names
         (pmap (partial update-with-tag :latlng :address geocode))))

  ;; The problem faced is that there are many varied ways to display
  ;;   locations and location labels at different pages, and this differs even within a site.
  ;; Even for a single site like thesmartlocal, different pages have different formatting,
  ;;   leading to many different expressions of location and location labels
  ;; location = the address, or perhaps just the shopping mall or unit number and road name,
  ;;            if full address information is missing
  ;; location labels = the label for this location, such as branch name "Bugis+",
  ;;   or the name of the recommendation, such as "13. Three Buns @ Potato Head Folk"

  (defn process-old
    [page]
    (let [raw-result (-> page
                         parse
                         as-hickory
                         gather-address-info
                         data-add-geocoding)
          result (publish raw-result)]
      #_(clojure.pprint/pprint
         (->> raw-result
              (map (partial simplify-datum))
              (sort-by (comp get-index :place))))
      result)))
