(ns net.cgrand.xforms
  "Extra transducers for Clojure"
  {:author "Christophe Grand"}
  (:refer-clojure :exclude [reduce into count for partition str juxt last])
  (:require [clojure.core :as clj]))

(defmacro for
  "Like clojure.core/for with the first expression being replaced by % (or _). Returns a transducer."
  [[binding %or_ & seq-exprs] body-expr]
  (assert (and (symbol? %or_) (#{"%" "_"} (name %or_)))
    "The second element of the comprehension vector must be % or _.")
  (let [rf (gensym 'rf)
        acc (gensym 'acc)
        pair? #(and (vector? %) (= 2 (clj/count %)))
        destructuring-pair? (every-pred pair?
                              #(not-any? (some-fn keyword? #{'&}) %))
        rpairs (clj/partition 2 (rseq (vec seq-exprs)))
        build (fn [init]
                (clj/reduce (fn [body [expr binding]]
                      (case binding
                        :let `(let ~expr ~body)
                        :when `(if ~expr ~body ~acc)
                        :while `(if ~expr ~body (reduced ~acc))
                        (if (destructuring-pair? binding)
                          `(let [expr# ~expr]
                             (if (and (map? expr#) (satisfies? clojure.core.protocols/IKVReduce expr#))
                               (clj/reduce-kv (fn [~acc ~@binding] ~body) ~acc expr#)
                               (clj/reduce (fn [~acc ~binding] ~body) ~acc expr#)))
                          `(clj/reduce (fn [~acc ~binding] ~body) ~acc ~expr))))
                  init rpairs))
        nested-reduceds (clj/for [[expr binding] rpairs
                                  :when (not (keyword? binding))] 
                          `reduced)
        body (build `(let [acc# (~rf ~acc ~@(if (and (pair? body-expr) (nil? (meta body-expr)))
                                              body-expr
                                              [body-expr]))]
                       (if (reduced? acc#)
                         (-> acc# ~@nested-reduceds)
                         acc#)))]
    `(fn [~rf]
       (let [~rf (ensure-kvrf ~rf)]
         (kvrf
           ([] (~rf))
           ([~acc] (~rf ~acc))
           ([~acc ~binding] ~body)
           ~(if (destructuring-pair? binding)
              `([~acc ~@binding] ~body)
              `([~acc k# v#]
                 (let [~binding (clojure.lang.MapEntry. k# v#)] ~body))))))))

(defprotocol KvRfable "Protocol for reducing fns that accept key and val as separate arguments."
  (some-kvrf [f] "Returns a kvrf or nil"))

(extend-protocol KvRfable
  Object (some-kvrf [_] nil)
  nil (some-kvrf [_] nil))

(defmacro kvrf [name? & fn-bodies]
  (let [name (if (symbol? name?) name? (gensym '_))
        fn-bodies (if (symbol? name?) fn-bodies (cons name? fn-bodies))
        fn-bodies (if (vector? (first fn-bodies)) (list fn-bodies) fn-bodies)]
    `(reify
       clojure.lang.Fn
       KvRfable
       (some-kvrf [this#] this#)
       clojure.lang.IFn
       ~@(clj/for [[args & body] fn-bodies]
           `(invoke [~name ~@args] ~@body)))))

(defn ensure-kvrf [rf]
  (or (some-kvrf rf)
    (kvrf
      ([] (rf))
      ([acc] (rf acc))
      ([acc x] (rf acc x))
      ([acc k v] (rf acc (clojure.lang.MapEntry. k v))))))

(defn reduce
  "A transducer that reduces a collection to a 1-item collection consisting of only the reduced result.
   Unlike reduce but like transduce it does call the completing arity (1) of the reducing fn."
  ([f]
    (fn [rf]
      (let [vacc (volatile! (f))]
        (let [f (ensure-kvrf f)]
          (kvrf
            ([] (rf))
            ([acc] (rf (unreduced (rf acc (f (unreduced @vacc))))))
            ([acc x]
              (if (reduced? (vswap! vacc f x))
                (reduced acc)
                acc))
            ([acc k v]
              (if (reduced? (vswap! vacc f k v))
                (reduced acc)
                acc)))))))
  ([f init]
    (reduce (fn ([] init) ([acc] (f acc)) ([acc x] (f acc x))))))

(defn- into-rf [to]
  (cond
    (instance? clojure.lang.IEditableCollection to)
    (if (map? to)
      (kvrf
        ([] (transient to))
        ([acc] (persistent! acc))
        ([acc x] (conj! acc x))
        ([acc k v] (assoc! acc k v)))
      (fn
        ([] (transient to))
        ([acc] (persistent! acc))
        ([acc x] (conj! acc x))))
    (map? to)
    (kvrf
      ([] to)
      ([acc] acc)
      ([acc x] (conj acc x))
      ([acc k v] (assoc acc k v)))
    :else
    (fn
      ([] to)
      ([acc] acc)
      ([acc x] (conj acc x)))))

(defn into
  "Like clojure.core/into but with a 1-arg arity returning a transducer which accumulate every input in a collection and outputs only the accumulated collection."
  ([to]
    (reduce (into-rf to)))
  ([to from]
    (into to identity from))
  ([to xform from]
    (let [rf (xform (into-rf to))]
      (if-let [rf (and (map? from) (satisfies? clojure.core.protocols/IKVReduce from) (some-kvrf rf))]
        (rf (clj/reduce-kv rf (rf) from))
        (rf (clj/reduce rf (rf) from))))))

(defmacro ^:private or-instance? [class x y]
  (let [xsym (gensym 'x_)]
    `(let [~xsym ~x]
       (if (instance? ~class ~xsym) ~(with-meta xsym {:tag class}) ~y))))

(defn str!
  "Like xforms/str but returns a StringBuilder."
  ([] (StringBuilder.))
  ([sb] (or-instance? StringBuilder sb (StringBuilder. (clj/str sb)))) ; the instance? checks are for compatibility with str in case of seeded reduce/transduce.
  ([sb x] (.append (or-instance? StringBuilder sb (StringBuilder. (clj/str sb))) x)))

(def str
  "Reducing function to build strings in linear time. Acts as replacement for clojure.core/str in a reduce/transduce call."
  (completing str! clj/str))

;; for both map entries and vectors 
(defn- key' [kv] (nth kv 0))
(defn- val' [kv] (nth kv 1))

(defn- nop-rf "The noop reducing function" ([acc] acc) ([acc _] acc) ([acc _ _] acc))

(defn- multiplexable
  "Returns a multiplexable reducing function (doesn't init or complete the uderlying rf, wraps reduced -- like preserving-reduced)"
  [rf]
  (let [rf (ensure-kvrf rf)]
    (kvrf
     ([])
     ([acc] acc) ; no init no complete rf
     ([acc x]
       (let [acc (rf acc x)]
         (if (reduced? acc)
           (reduced acc)
           acc)))
     ([acc k v]
       (let [acc (rf acc k v)]
         (if (reduced? acc)
           (reduced acc)
           acc))))))

(defn by-key
  "Returns a transducer which partitions items according to kfn.
   It applies the transform specified by xform to each partition.
   Partitions contain the \"value part\" (as returned by vfn) of each item.
   The resulting transformed items are wrapped back into a \"pair\" using the pair function.
   Default values for kfn, vfn and pair are first, second (or identity if kfn is specified) and vector."
  ([xform] (by-key nil nil vector xform))
  ([kfn xform] (by-key kfn identity vector xform))
  ([kfn vfn xform] (by-key kfn vfn vector xform))
  ([kfn vfn pair xform]
    (let [pair (if (identical? vector pair) ::default pair)]
      (fn [rf]
        (let [mrf (multiplexable rf)
              make-rf (cond
                        (nil? pair) (constantly mrf)
                        (= ::default pair)
                        (fn [k] (fn ([acc] acc) ([acc v] (mrf acc k v))))
                        :else (fn [k] (fn ([acc] acc) ([acc v] (mrf acc (pair k v))))))
              m (volatile! (transient {}))]
          (if (and (nil? kfn) (nil? vfn))
            (kvrf self
              ([] (rf))
              ([acc] (rf (clj/reduce (fn [acc krf] (krf acc)) acc (vals (persistent! @m)))))
              ([acc x]
                (self acc (key' x) (val' x)))
              ([acc k v]
                (let [krf (or (get @m k) (doto (xform (make-rf k)) (->> (vswap! m assoc! k))))
                      acc (krf acc v)]
                   (if (reduced? acc)
                     (if (reduced? @acc)
                       (do
                         (vreset! m (transient {})) ; no need to run completions
                         @acc) ; downstream is done, propagate
                       (do
                         (vswap! m assoc! k nop-rf)
                         (krf @acc))) ; TODO think again
                     acc))))
            (let [kfn (or kfn key')
                  vfn (or vfn val')]
              (fn
                ([] (rf))
                ([acc] (rf (clj/reduce (fn [acc krf] (krf acc)) acc (vals (persistent! @m)))))
                ([acc x]
                  (let [k (kfn x)
                        krf (or (get @m k) (doto (xform (make-rf k)) (->> (vswap! m assoc! k))))
                        acc (krf acc (vfn x))]
                    (if (reduced? acc)
                      (if (reduced? @acc)
                        (do
                          (vreset! m (transient {})) ; no need to run completions
                          @acc) ; downstream is done, propagate
                        (do
                          (vswap! m assoc! k nop-rf)
                          (krf @acc)))
                      acc)))))))))))

(defn partition
  "Returns a partitioning transducer. Each partition is independently transformed using the xform transducer."
  ([n]
    (partition n n (into [])))
  ([n step-or-xform]
    (if (fn? step-or-xform)
      (partition n n step-or-xform)
      (partition n step-or-xform (into []))))
  ([n step pad-or-xform]
    (if (fn? pad-or-xform)
      (let [xform pad-or-xform]
        (fn [rf]
          (let [mxrf (multiplexable rf)
                dq (java.util.ArrayDeque. n)
                barrier (volatile! n)
                xform (comp (map #(if (identical? dq %) nil %)) xform)]
            (fn
              ([] (rf))
              ([acc] (rf acc))
              ([acc x]
                (let [b (vswap! barrier dec)]
                  (when (< b n) (.add dq (if (nil? x) dq x)))
                  (if (zero? b)
                    ; this transduce may return a reduced because of mxrf wrapping reduceds coming from rf
                    (let [acc (transduce xform mxrf acc dq)]
                      (dotimes [_ (min n step)] (.poll dq))
                      (vswap! barrier + step)
                      acc)
                    acc)))))))
      (partition n step pad-or-xform (into []))))
  ([n step pad xform]
    (fn [rf]
      (let [mxrf (multiplexable rf)
            dq (java.util.ArrayDeque. n)
            barrier (volatile! n)
            xform (comp (map #(if (identical? dq %) nil %)) xform)]
        (fn
          ([] (rf))
          ([acc] (if (< @barrier n)
                   (let [xform (comp cat (take n) xform)]
                     ; don't use mxrf for completion: we want completion and don't want reduced-wrapping 
                     (transduce xform rf acc [dq pad]))
                   acc))
          ([acc x]
            (let [b (vswap! barrier dec)]
              (when (< b n) (.add dq (if (nil? x) dq x)))
              (if (zero? b)
                ; this transduce may return a reduced because of mxrf wrapping reduceds coming from rf
                (let [acc (transduce xform mxrf acc dq)]
                  (dotimes [_ (min n step)] (.poll dq))
                  (vswap! barrier + step)
                  acc)
                acc))))))))

(defn avg
  "Reducing fn to compute the arithmetic mean."
  ([] (transient [0 0]))
  ([[n sum]] (/ sum n))
  ([acc x] (avg acc x 1))
  ([[n sum :as acc] x w]
    (-> acc (assoc! 0 (+ n w)) (assoc! 1 (+ sum (* w x))))))

(defn window
  "Returns a transducer which computes an accumulator over the last n items
   using two functions: f and its inverse invf.

   The accumulator is initialized with (f).
   It is updated to (f (invf acc out) in) where \"acc\" is the current value,
   \"in\" the new item entering the window, \"out\" the item exiting the window.
   The value passed to the dowstream reducing function is (f acc) enabling acc to be
   mutable and 1-arity f to project its state to a value.

   If you don't want to see the accumulator until the window is full then you need to
   use (drop (dec n)) to remove them.

   If you don't have an inverse function, consider using partition and reduce: 
   (x/partition 4 (x/reduce rf))"
  [n f invf]
  (fn [rf]
    (let [ring (object-array n)
          vi (volatile! (- n))
          vwacc (volatile! (f))]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc x]
          (let [i @vi
                wacc @vwacc] ; window accumulator
            (if (neg? i) ; not full yet
              (do
                (aset ring (+ n i) x)
                (vreset! vi (inc i))
                (rf acc (f (vreset! vwacc (f wacc x)))))
              (let [x' (aget ring i)]
                (aset ring i x)
                (vreset! vi (let [i (inc i)] (if (= n i) 0 i)))
                (rf acc (f (vreset! vwacc (f (invf wacc x') x))))))))))))

(defn window-by-time
  "Returns a transducer which computes a windowed accumulator over chronologically sorted items.
   
   timef is a function from one item to its scaled timestamp (as a double). The window length is always 1.0
   so timef must normalize timestamps. For example if timestamps are in seconds (and under the :ts key),
   to get a 1-hour window you have to use (fn [x] (/ (:ts x) 3600.0)) as timef.

   n is the integral number of steps by which the window slides. With a 1-hour window, 4 means that the window slides every 15 minutes.

   f and invf work like in #'window."
  ([timef n f]
    (window-by-time timef n 
      (fn 
        ([] clojure.lang.PersistentQueue/EMPTY)
        ([q] (f (clj/reduce f (f) q)))
        ([q x] (conj q x)))
      (fn [q _] (pop q))))
  ([timef n f invf]
    (let [timef (fn [x] (long (Math/floor (* n (timef x)))))]
      (fn [rf]
       (let [dq (java.util.ArrayDeque.)
             vwacc (volatile! (f))
             flush!
             (fn [acc ^long from-ts ^long to-ts]
               (loop [ts from-ts acc acc wacc @vwacc]
                 (let [x (.peekFirst dq)]
                   (cond
                     (= ts (timef x))
                     (do
                       (.pollFirst dq)
                       (recur ts acc (invf wacc x)))
                     (= ts to-ts)
                     (do
                       (vreset! vwacc wacc)
                       acc)
                     :else
                     (let [acc (rf acc (f wacc))]
                       (if (reduced? acc)
                         (do
                           (vreset! vwacc wacc)
                           acc)
                         (recur (inc ts) acc wacc)))))))]
         (fn
           ([] (rf))
           ([acc]
             (let [acc (if-not (.isEmpty dq)
                         (unreduced (rf acc (f @vwacc)))
                         acc)]
               (rf acc)))
           ([acc x]
             (let [limit (- (timef x) n)
                   prev-limit (if-some [prev-x (.peekLast dq)]
                                (- (timef prev-x) n)
                                limit)
                   _ (.addLast dq x) ; so dq is never empty for flush!
                   acc (flush! acc prev-limit limit)]
               (when-not (reduced? acc)
                 (vswap! vwacc f x))
               acc))))))))

(defn count [rf]
  (let [n (java.util.concurrent.atomic.AtomicLong.)] 
    (fn
      ([] (rf))
      ([acc] (rf (unreduced (rf acc (.get n)))))
      ([acc _] (.incrementAndGet n) acc))))

(defn juxt
  "Returns a reducing fn which compute all rfns at once and whose final return
   value is a vector of the final return values of each rfns."
  [& rfns]
  (let [rfns (mapv ensure-kvrf rfns)]
    (kvrf
      ([] (mapv #(vector % (volatile! (%))) rfns))
      ([acc] (mapv (fn [[rf vacc]] (rf (unreduced @vacc))) acc))
      ([acc x]
        (let [some-unreduced (clj/reduce (fn [some-unreduced [rf vacc]] 
                                           (when-not (reduced? @vacc) (vswap! vacc rf x) true))
                               false acc)]
          (if some-unreduced acc (reduced acc))))
      ([acc k v]
        (let [some-unreduced (clj/reduce (fn [some-unreduced [rf vacc]] 
                                           (when-not (reduced? @vacc) (vswap! vacc rf k v) true))
                               false acc)]
          (if some-unreduced acc (reduced acc)))))))

(defn multiplex
  "Returns a transducer that runs several transducers (sepcified by xforms) in parallel.
   If xforms is a map, values of the map are transducers and keys are used to tag each
   transducer output:
   => (into [] (x/multiplex [(map inc) (map dec)]) (range 3))
   [1 -1 2 0 3 1] ; no map, no tag
   => (into [] (x/multiplex {:up (map inc) :down (map dec)}) (range 3))
   [[:up 1] [:down -1] [:up 2] [:down 0] [:up 3] [:down 1]]"
  [xforms]
  (fn [rf]
    (let [mrf (multiplexable (ensure-kvrf rf))
          rfs (volatile! (if (map? xforms)
                           (into {} (for [[k xform] %
                                          :let [xform (comp xform (for [x %] [k x]))]]
                                      [k (xform mrf)])
                             xforms)
                         (into #{} (map #(% mrf)) xforms)))
          invoke-rfs (if (map? xforms)
                       (fn [acc invoke]
                         (reduce-kv
                           (fn [acc tag rf]
                             (let [acc (invoke rf acc)]
                               (if (reduced? acc)
                                 (if (reduced? @acc)
                                   (do
                                     (vreset! rfs nil)
                                     acc) ; downstream is done, propagate
                                   (do (vswap! rfs dissoc tag) (rf @acc)))
                                 acc)))
                           acc @rfs))
                       (fn [acc invoke]
                         (clj/reduce
                           (fn [acc rf]
                             (let [acc (invoke rf acc)]
                               (if (reduced? acc)
                                 (if (reduced? @acc)
                                   (do
                                     (vreset! rfs nil)
                                     acc) ; downstream is done, propagate
                                   (do (vswap! rfs disj rf) (rf @acc)))
                                 acc)))
                           acc @rfs)))]
      (kvrf
        ([] (rf))
        ([acc] (rf (invoke-rfs acc #(%1 %2))))
        ([acc x]
          (let [acc (invoke-rfs acc #(%1 %2 x))]
            (if (zero? (clj/count @rfs))
              (ensure-reduced acc)
              acc)))
        ([acc k v]
          (let [acc (invoke-rfs acc #(%1 %2 k v))]
            (if (zero? (clj/count @rfs))
              (ensure-reduced acc)
              acc)))))))

(defn juxt-map
  [& key-rfns]
  (let [f (apply juxt (take-nth 2 (next key-rfns)))
        keys (vec (take-nth 2 key-rfns))]
    (let [f (ensure-kvrf f)]
      (kvrf
        ([] (f))
        ([acc] (zipmap keys (f acc)))
        ([acc x] (f acc x))
        ([acc k v] (f acc k v))))))

(defn last
  "Reducing function that returns the last value."
  ([] nil)
  ([x] x)
  ([_ x] x))

(defn transjuxt
  "Performs several transductions over coll at once. xforms-map can be a map or a sequential collection.
   When xforms-map is a map, returns a map with the same keyset as xforms-map.
   When xforms-map is a sequential collection returns a vector of same length as xforms-map.
   Returns a transducer when coll is omitted."
  ([xforms-map]
    (let [collect-xform (if (map? xforms-map) 
                          (into {})
                          (reduce (kvrf
                                    ([] (clj/reduce (fn [v _] (conj! v nil))
                                          (transient []) (range (count xforms-map))))
                                    ([v] (persistent! v))
                                    ([v i x] (assoc! v i x)))))
          xforms-map (if (map? xforms-map) xforms-map (zipmap (range xforms-map)))]
      (comp
        (multiplex (into {} (by-key (map #(comp % (take 1)))) xforms-map))
        collect-xform)))
  ([xforms-map coll]
    (transduce (transjuxt xforms-map) last coll)))

;; map stuff
(defn update 
  ([m k xform]
    (update m k xform nil))
  ([m k xform not-found]
    (let [rf (xform (fn ([m] m) ([m v] (assoc m k v))))]
      (rf (unreduced (rf (dissoc m k) (get m k not-found)))))))