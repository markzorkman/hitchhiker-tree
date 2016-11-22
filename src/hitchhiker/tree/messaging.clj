(ns hitchhiker.tree.messaging
  (:refer-clojure :exclude [subvec])
  (:require [clojure.core.rrb-vector :refer [catvec]]
            [clojure.pprint :as pp]
            [hitchhiker.tree.core :as core])
  (:import java.io.Writer))

;; An operation is an object with a few functions
;; 1. It has a function that it applies to the tree to apply its effect
;; In the future, it could also have
;; 2. It has a promise which can be filled with the end result
;;       (more memory but faster results for repeat queries)

(defprotocol IOperation
  (affects-key [op] "Which key this affects--currently must be a single key")
  (apply-op-to-coll [op coll] "Applies the operation to the collection")
  (apply-op-to-tree [op tree] "Applies the operation to the tree"))

(defrecord InsertOp [key value]
  IOperation
  (affects-key [_] key)
  (apply-op-to-coll [_ map] (assoc map key value))
  (apply-op-to-tree [_ tree] (core/insert tree key value)))

(defrecord DeleteOp [key]
  IOperation
  (affects-key [_] key)
  (apply-op-to-coll [_ map] (dissoc map key))
  (apply-op-to-tree [_ tree] (core/delete tree key)))

(defmethod print-method InsertOp
  [op ^Writer writer]
  (.write writer "InsertOp")
  (.write writer (str {:key (:key op) :value (:value op) " - " (:tag op)})))

(defmethod print-dup InsertOp
  [op ^Writer writer]
  (.write writer "(tree.messaging/->InsertOp ")
  (.write writer (pr-str (:key op)))
  (.write writer ", ")
  (.write writer (pr-str (:value op)))
  (.write writer ")"))

(defmethod pp/simple-dispatch InsertOp
  [op]
  (print op))

(defmethod print-method DeleteOp
  [op ^Writer writer]
  (.write writer "DeleteOp")
  (.write writer (str {:key (:key op)} " - " (:tag op))))

(defmethod print-dup DeleteOp
  [op ^Writer writer]
  (.write writer "(tree.messaging/->DeleteOp ")
  (.write writer (pr-str (:key op)))
  (.write writer ")"))

(defmethod pp/simple-dispatch DeleteOp
  [op]
  (print op))

(defn enqueue3 [tree msgs deferred-ops]
  (let [tree (core/resolve tree)]
    (cond
      (core/data-node? tree) ; need to return ops to apply to the tree proper...
      (do (swap! deferred-ops into msgs)
          tree)
      (<= (+ (count msgs) (count (:op-buf tree)))
          (get-in tree [:cfg :op-buf-size])) ; will there be enough space?
      (-> tree
          (core/dirty!)
          (update-in [:op-buf] into msgs))
      :else ; overflow, should be IndexNode
      (do (assert (core/index-node? tree))
          ;(println "overflowing node" (:keys tree) "with buf" (:op-buf tree)
          ;         "with new msgs" msgs
          ;         )
          (loop [[child & children] (:children tree)
                 rebuilt-children []
                 msgs (vec (sort-by affects-key ;must be a stable sort
                                    (concat (:op-buf tree) msgs)))]
            (let [took-msgs (into []
                                  (take-while #(>= 0 (core/compare
                                                       (affects-key %)
                                                       (core/last-key child))))
                                  msgs)
                  extra-msgs (into []
                                   (drop-while #(>= 0 (core/compare
                                                        (affects-key %)
                                                        (core/last-key child))))
                                   msgs)
                  ;_ (println "last-key:" (core/last-key child))
                  ;_ (println "goes left:" took-msgs)
                  ;_ (println "goes right:" extra-msgs)
                  on-the-last-child? (empty? children)

                  ;; Any changes to the current child?
                  new-child
                  (cond
                    (and on-the-last-child? (seq extra-msgs))
                    (enqueue3 (core/resolve child)
                              (catvec took-msgs extra-msgs)
                              deferred-ops)
                    (seq took-msgs) ; save a write
                    (enqueue3 (core/resolve child)
                              took-msgs
                              deferred-ops)
                    :else
                    child)]

              (if on-the-last-child?
                (-> tree
                    (assoc :children (conj rebuilt-children new-child))
                    (assoc :op-buf [])
                    (core/dirty!))
                (recur children (conj rebuilt-children new-child) extra-msgs))))))))

(defn enqueue2 [tree msgs]
  (let [deferred-ops (atom [])
        msg-buffers-propagated (enqueue3 tree msgs deferred-ops)]
    ;(when (seq @deferred-ops) (println "appyling deferred ops" @deferred-ops))
    (reduce (fn [tree op]
              (apply-op-to-tree op tree))
            msg-buffers-propagated
            @deferred-ops)))

(defn enqueue
  ([tree msgs]
   (enqueue2 tree msgs))
  ([tree msgs deferred-ops]
   ;(println "tree is" (class tree) tree)
   (enqueue3 tree msgs deferred-ops)))


;;TODO delete in core needs to stop using the index-node constructor to be more
;;careful about how we handle op-bufs during splits and merges.
;;
;;After we've got delete working, lookup, pred, and succ should be fixed
;;
;;broadcast nodes will need IDs so that they can combine during merges...
;;


(defn apply-ops-in-path
  [path]
  (if (>= 1 (count path))
    (:children (peek path))
    (let [ops (->> path
                   (into [] (comp (filter core/index-node?)
                                  (map :op-buf)))
                   (rseq) ; highest node should be last in seq
                   (apply concat)
                   (sort-by affects-key)) ;must be a stable sort
          this-node-index (-> path pop peek)
          parent (-> path pop pop peek)
          is-first? (zero? this-node-index)
          ;;We'll need to find the smallest last-key of the left siblings along the path
          [left-sibs-on-path is-last?]
          (loop [path path
                 is-last? true
                 left-sibs []]
            (if (= 1 (count path)) ; are we at the root?
              [left-sibs is-last?]
              (let [this-node-index (-> path pop peek)
                    parent (-> path pop pop peek)
                    is-first? (zero? this-node-index)
                    local-last? (= (-> parent :children count dec)
                                   this-node-index)]
                (if is-first?
                  (recur (pop (pop path)) (and is-last? local-last?) left-sibs)
                  (recur (pop (pop path))
                         (and is-last? local-last?)
                         (conj left-sibs
                               (nth (:children parent)
                                    (dec this-node-index))))))))
          left-sibs-min-last (when (seq left-sibs-on-path)
                               (->> left-sibs-on-path
                                    (map core/last-key)
                                    (apply max)))
          left-sib-filter (if left-sibs-min-last
                            (drop-while #(>= 0 (core/compare (affects-key %)
                                                             left-sibs-min-last)))
                            identity)
          data-node (peek path)
          my-last (core/last-key data-node)
          right-side-filter (if is-last?
                              identity
                              (take-while #(>= 0 (core/compare (affects-key %) my-last))))
          correct-ops (into [] (comp left-sib-filter right-side-filter) ops)]

          ;;We include op if leq my left, and not if leq left's left
          ;;TODO we can't apply all ops, we should ensure to only apply ops whose keys are in the defined range, unless we're the last sibling

      ;(println "left-sibs-min-last" left-sibs-min-last)
      ;(println "is-last?" is-last?)
      ;(println "expanding data node" data-node "with ops" correct-ops)
      (reduce (fn [coll op]
                (apply-op-to-coll op coll))
              (:children data-node)
              correct-ops))))

(defn lookup
  ([tree key]
   (lookup tree key nil))
  ([tree key not-found]
   (let [path (core/lookup-path tree key)
         expanded (apply-ops-in-path path)]
     (get expanded key not-found))))

(def uuid (java.util.UUID/randomUUID))
(def counter! (atom 0))

(defn insert
  [tree key value]
  (enqueue tree [(assoc (->InsertOp key value)
                        :tag (str uuid ":" (swap! counter! inc)))]))


(defn delete
  [tree key]
  (enqueue tree [(assoc (->DeleteOp key)
                        :tag (java.util.UUID/randomUUID))]))


(defn forward-iterator
  "Takes the result of a search and returns an iterator going
   forward over the tree. Does lg(n) backtracking sometimes."
  [path]
  (assert (core/data-node? (peek path)))
  (let [first-elements (apply-ops-in-path path)
        next-elements (lazy-seq
                        (when-let [succ (core/right-successor (pop path))]
                          (forward-iterator succ)))]
    (concat first-elements next-elements)))

(defn lookup-fwd-iter
  [tree key]
  (let [path (core/lookup-path tree key)]
    (when path
      (drop-while (fn [[k v]]
                    (neg? (core/compare k key)))
                  (forward-iterator path)))))
