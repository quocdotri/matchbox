(ns matchbox.core
  (:refer-clojure :exclude [get-in set! reset! conj! swap! dissoc! deref parents key])
  #+clj
  (:import [com.firebase.client
            ServerValue
            Firebase
            FirebaseError
            MutableData
            DataSnapshot
            ValueEventListener
            ChildEventListener
            Transaction
            Transaction$Handler])
  (:require [clojure.string :as str]
            [matchbox.utils :as utils]
            [matchbox.registry :refer [register-listener]]
            #+clj [clojure.walk :as walk]
            #+cljs cljsjs.firebase))

;; TODO: JVM register + unsubscribe listeners
;; TODO: JVM connect/discconet/on-disconnect
;; TODO: JVM auth

;; constants

(def child-events
  [:child-added
   :child-changed
   :child-moved
   :child-removed])

(def all-events
  (conj child-events :value))

#+cljs (def undefined) ;; firebase methods do not take kindly to nil callbacks

(def SERVER_TIMESTAMP
  #+clj ServerValue/TIMESTAMP
  #+cljs js/Firebase.ServerValue.TIMESTAMP)

;; helpers

(declare wrap-snapshot)
(declare hydrate)
(declare reset!)

#+clj
(defn- wrap-cb [cb]
  (reify com.firebase.client.Firebase$CompletionListener
    (^void onComplete [this ^FirebaseError err ^Firebase ref]
      (if err (throw err) (cb ref)))))

#+clj
(defn- reify-value-listener [cb]
  (reify ValueEventListener
    (^void onDataChange [_ ^DataSnapshot ds]
      (cb (wrap-snapshot ds)))
    (^void onCancelled [_ ^FirebaseError err]
      (throw err))))

#+clj
(defn- build-tx-handler [f args cb]
  (reify Transaction$Handler
    (^com.firebase.client.Transaction$Result doTransaction [_ ^MutableData d]
      (let [current (hydrate (.getValue d))]
        (reset! d (apply f current args))
        (Transaction/success d)))
    (^void onComplete [_ ^FirebaseError error, ^boolean committed, ^DataSnapshot d]
      (if (and cb (not error) committed)
        (cb (hydrate (.getValue d)))))))

#+clj
(defn reify-child-listener [{:keys [added changed moved removed]}]
  (reify ChildEventListener
    (^void onChildAdded [_ ^DataSnapshot d ^String previous-child-name]
      (if added (added (wrap-snapshot d))))
    (^void onChildChanged [_ ^DataSnapshot d ^String previous-child-name]
      (if changed (changed (wrap-snapshot d))))
    (^void onChildMoved [_ ^DataSnapshot d ^String previous-child-name]
      (if moved (moved (wrap-snapshot d))))
    (^void onChildRemoved [_ ^DataSnapshot d]
      (if removed (removed (wrap-snapshot d))))
    (^void onCancelled [_ ^FirebaseError err]
      (throw err))))

#+clj
(defn- strip-prefix [type]
  (-> type name (str/replace #"^.+\-" "") keyword))


(defn hydrate [v]
  #+clj (if (map? v) (walk/keywordize-keys v) v)
  #+cljs (js->clj v :keywordize-keys true))

(defn serialize [v]
  #+clj (if (map? v) (walk/stringify-keys v) v)
  #+cljs (clj->js v))

(defn key
  "Last segment in reference or snapshot path"
  [ref]
  #+clj (.getKey ref)
  #+cljs (.key ref))

(defn value
  "Data stored within snapshot"
  [snapshot]
  (hydrate
    #+clj (.getValue snapshot)
    #+cljs (.val snapshot)))

(defn- wrap-snapshot [snapshot]
  ;; TODO: enhance with snapshot protocol
  [(key snapshot) (value snapshot)])

;; API

(defn connect
  "Create a reference for firebase"
  [url]
  #+clj (Firebase. url)
  #+cljs (js/Firebase. url))

(defn get-in
  "Obtain child reference from base by following korks"
  [ref korks]
  (let [path (utils/korks->path korks)]
    (if-not (seq path) ref (.child ref path))))

(defn parent
  "Immediate ancestor of reference, if any"
  [ref]
  (and ref
       #+clj (.getParent ref)
       #+cljs (.parent ref)))

(defn parents
  "Probably don't need this. Or maybe we want more zipper nav (siblings, in-order, etc)"
  [ref]
  (take-while identity (iterate parent (parent ref))))

(defn deref [ref cb]
  #+clj (.addListenerForSingleValueEvent ref (reify-value-listener cb))
  #+cljs (.once ref "value" (comp cb value)))

(defn reset! [ref val & [cb]]
  #+clj
  (if-not cb
    (.setValue ref (serialize val))
    (.setValue ref (serialize val) (wrap-cb cb)))
  #+cljs (.set ref (serialize val) (or cb undefined)))

;; FIXME: support for JVM
#+cljs
(defn reset-with-priority! [ref val priority & [cb]]
  (.setWithPriority ref (serialize val) priority (or cb undefined)))

(defn merge! [ref val & [cb]]
  #+clj
  (if-not cb
    (.updateChildren ref (serialize val))
    (.updateChildren ref (serialize val) (wrap-cb cb)))
  #+cljs
  (.update ref (serialize val) (or cb undefined)))

(defn conj! [ref val & [cb]]
  #+clj (reset! (.push ref) val cb)
  #+cljs (.push ref (serialize val) (or cb undefined)))

(defn swap!
  "Update value atomically, with local optimistic writes"
  [ref f & args]
  (let [[cb args] (utils/extract-cb args)]
    #+clj
    (.runTransaction ref (build-tx-handler f args cb) true)
    #+cljs
    (let [f' #(-> % hydrate ((fn [x] (apply f x args))) serialize)]
      (.transaction ref f' (or cb undefined)))))

(defn dissoc! [ref & [cb]]
  #+clj
  (if-not cb
    (.removeValue ref)
    (.removeValue ref (wrap-cb cb)))
  #+cljs
  (.remove ref (or cb undefined)))

(def remove! dissoc!)

(defn set-priority! [ref priority & [cb]]
  #+clj
  (if-not cb
    (.setPriority ref priority)
    (.setPriority ref priority (wrap-cb cb)))
  #+cljs
  (.setPriority ref priority (or cb undefined)))

;;

(defonce connected (atom true))

(defn disconnect! []
  #+cljs (.goOffline js/Firebase)
  (clojure.core/reset! connected false))

(defn reconnect! []
  #+cljs (.goOnline js/Firebase)
  (clojure.core/reset! connected true))

(defn check-connected?
  "Returns boolean around whether client is set to synchronise with server.
   Says nothing about actual connectivity."
  []
  @connected)

;; FIXME: find a better abstraction
;; https://github.com/crisptrutski/matchbox/issues/4

(defn on-disconnect
  "Return an on"
  [ref]
  #+cljs (.onDisconnect ref))

(defn cancel [ref-disconnect & [cb]]
  #+cljs (.cancel ref-disconnect (or cb undefined)))

;; --------------------
;; auth

(defn build-opts [session-only?]
  #+cljs (if session-only?
           #js {:remember "sessionOnly"}
           undefined))

(defn- wrap-auth-cb [cb]
  #+cljs (if cb
           (fn [err info]
             (cb err (hydrate info)))
           undefined))

(defn auth [ref email password & [cb session-only?]]
  #+cljs (.authWithPassword
          ref
          #js {:email email, :password password}
          (wrap-auth-cb cb)
          (build-opts session-only?)))

(defn auth-anon [ref & [cb session-only?]]
  #+cljs (.authAnonymously
          ref
          (wrap-auth-cb cb)
          (build-opts session-only?)))

(defn auth-info
  "Returns a map of uid, provider, token, expires - or nil if there is no session"
  [ref]
  #+cljs (hydrate (.getAuth ref)))

;; onAuth and offAuth are not wrapped yet

(defn unauth [ref]
  #+cljs (.unauth ref))

;; nested variants

(defn deref-in [ref korks & [cb]]
  (deref (get-in ref korks) cb))

(defn reset-in! [ref korks val & [cb]]
  (reset! (get-in ref korks) val cb))

#+cljs
(defn reset-with-priority-in! [ref korks val priority & [cb]]
  (reset-with-priority! (get-in ref korks) val priority cb))

(defn merge-in! [ref korks val & [cb]]
  (merge! (get-in ref korks) val cb))

(defn conj-in! [ref korks val & [cb]]
  (conj! (get-in ref korks) val cb))

(defn swap-in! [ref korks f & [cb]]
  (swap! (get-in ref korks) f cb))

(defn dissoc-in! [ref korks & [cb]]
  (dissoc! (get-in ref korks) cb))

(def remove-in! remove-in!)

(defn set-priority-in! [ref korks priority & [cb]]
  (set-priority! (get-in ref korks) priority cb))

;; ------------------
;; subscriptions

(defn- -listen-to [ref type cb]
  (assert (some #{type} all-events) (str "Unknown type: " type))
  #+clj
  (if-not (some #{type} child-events)
    (.addValueEventListener ref (reify-value-listener cb))
    (.addChildEventListener ref (reify-child-listener
                                  (hash-map (strip-prefix type) cb))))
  #+cljs
  (let [type (utils/kebab->underscore type)]
    (let [cb' (comp cb wrap-snapshot)
          unsub! #(.off ref type cb')]
      (.on ref type cb')
      (register-listener ref type unsub!)
      unsub!)))

(defn- -listen-children [ref cb]
  #+clj
  (let [bases (map strip-prefix child-events)
        cbs (zipmap bases (->> child-events
                            (map (fn [type] #(vector type %)))
                            (map #(comp cb %))))]
    (.addChildEventListener ref (reify-child-listener cbs)))
  #+cljs
  (let [cbs (->> child-events
                 (map (fn [type] #(vector type %)))
                 (map #(comp cb %)))
        unsubs (doall (map -listen-to (repeat ref) child-events cbs))]
    (fn []
      (doseq [unsub! unsubs]
        (unsub!)))))

(defn listen-to
  "Subscribe to notifications of given type"
  ([ref type cb] (-listen-to ref type cb))
  ([ref korks type cb] (-listen-to (get-in ref korks) type cb)))

(defn listen-children
  "Subscribe to all children notifications on a reference, and return an unsubscribe"
  ([ref cb] (-listen-children ref cb))
  ([ref korks cb] (-listen-children (get-in ref korks) cb)))