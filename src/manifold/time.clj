(ns
  ^{:author "Zach Tellman"
    :doc "This namespace contains methods for converting units of time, with milliseconds as the base representation, and for deferring execution of functions to some time in the future.  In practice, the methods here are not necessary to use Manifold effectively - `manifold.deferred/timeout` and `manifold.stream/periodically` are more directly useful - but they are available for anyone who should need them."}
  manifold.time
  (:require
    [manifold.utils :as utils]
    [clojure.string :as str])
  (:import
    [java.util
     Calendar
     TimeZone]
    [java.util.concurrent
     Future
     Executor
     Executors
     TimeUnit
     ScheduledThreadPoolExecutor
     TimeoutException]))

(defn nanoseconds
  "Converts nanoseconds -> milliseconds"
  [n]
  (/ n 1e6))

(defn microseconds
  "Converts microseconds -> milliseconds"
  [n]
  (/ n 1e3))

(defn milliseconds
  "Converts milliseconds -> milliseconds"
  [n]
  n)

(defn seconds
  "Converts seconds -> milliseconds"
  [n]
  (* n 1e3))

(defn minutes
  "Converts minutes -> milliseconds"
  [n]
  (* n 6e4))

(defn hours
  "Converts hours -> milliseconds"
  [n]
  (* n 36e5))

(defn days
  "Converts days -> milliseconds"
  [n]
  (* n 864e5))

(defn hz
  "Converts frequency -> period in milliseconds"
  [n]
  (/ 1e3 n))

(let [intervals (partition 2
                  ["d" (days 1)
                   "h" (hours 1)
                   "m" (minutes 1)
                   "s" (seconds 1)])]

  (defn format-duration
    "Takes a duration in milliseconds, and returns a formatted string
     describing the interval, i.e. '5d 3h 1m'"
    [n]
    (loop [s "", n n, intervals intervals]
      (if (empty? intervals)
        (if (empty? s)
          "0s"
          (str/trim s))
        (let [[desc val] (first intervals)]
          (if (>= n val)
            (recur
              (str s (int (/ n val)) desc " ")
              (rem n val)
              (rest intervals))
            (recur s n (rest intervals))))))))

(let [sorted-units [:millisecond Calendar/MILLISECOND
                    :second Calendar/SECOND
                    :minute Calendar/MINUTE
                    :hour Calendar/HOUR
                    :day Calendar/DAY_OF_YEAR
                    :week Calendar/WEEK_OF_MONTH
                    :month Calendar/MONTH]
      unit->calendar-unit (apply hash-map sorted-units)
      units (->> sorted-units (partition 2) (map first))
      unit->cleared-fields (zipmap
                             units
                             (map
                               #(->> (take % units) (map unit->calendar-unit))
                               (range (count units))))]

  (defn floor
    "Takes a `timestamp`, and rounds it down to the nearest even multiple of the `unit`.

         (floor 1001 :second) => 1000
         (floor (seconds 61) :minute) => 60000

     "
    [timestamp unit]
    (assert (contains? unit->calendar-unit unit))
    (let [^Calendar cal (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                          (.setTimeInMillis timestamp))]
      (doseq [field (unit->cleared-fields unit)]
        (.set cal field 0))
      (.getTimeInMillis cal)))

  (defn add
    "Takes a `timestamp`, and adds `value` multiples of `unit` to the value."
    [timestamp value unit]
    (assert (contains? unit->calendar-unit unit))
    (let [^Calendar cal (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                          (.setTimeInMillis timestamp))]
      (.add cal (unit->calendar-unit unit) value)
      (.getTimeInMillis cal))))

;;;

(in-ns 'manifold.deferred)
(clojure.core/declare success! error! deferred)
(in-ns 'manifold.time)

;;;

(let [num-cores     (.availableProcessors (Runtime/getRuntime))
      cnt           (atom 0)
      scheduler     (delay
                      (ScheduledThreadPoolExecutor.
                        num-cores
                        (utils/thread-factory (constantly "manifold-scheduler-queue"))))]

  (defn in
    "Schedules no-arg function `f` to be invoked in `interval` milliseconds.  Returns a deferred
     representing the returned value of the function."
    [^double interval f]
    (let [d (manifold.deferred/deferred)
          f (fn []
              (try
                (manifold.deferred/success! d (f))
                (catch Throwable e
                  (manifold.deferred/error! d e))))]
      (.schedule ^ScheduledThreadPoolExecutor @scheduler
        ^Runnable f
        (long (* interval 1e3))
        TimeUnit/MICROSECONDS)
      d))

  (defn every
    "Schedules no-arg function `f` to be invoked every `period` milliseconds, after `initial-delay`
     milliseconds, which defaults to `0`.  Returns a zero-argument function which, when invoked,
     cancels the repeated invocation.

     If the invocation of `f` ever throws an exception, repeated invocation is automatically
     cancelled."
    ([period f]
      (every period 0 f))
    ([period initial-delay f]
      (let [future-ref (promise)
            f (fn []
                (try
                  (f)
                  (catch Throwable e
                    (let [^Future future @future-ref]
                      (.cancel future false))
                    (throw e))))]
        (deliver future-ref
          (.scheduleAtFixedRate ^ScheduledThreadPoolExecutor @scheduler
            ^Runnable f
            (long (* initial-delay 1e3))
            (long (* period 1e3))
            TimeUnit/MICROSECONDS))
        (fn []
          (let [^Future future @future-ref]
            (.cancel future false)))))))

(defn at
  "Schedules no-arg function  `f` to be invoked at `timestamp`, which is the milliseconds
   since the epoch.  Returns a deferred representing the returned value of the function."
  [timestamp f]
  (in (max 0 (- timestamp (System/currentTimeMillis))) f))
