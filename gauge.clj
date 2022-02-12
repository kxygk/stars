;; allows for loading libraries dynamically
(use 'clojure.tools.deps.alpha.repl)


;; Add a bunch of libraries (might not be using all of these..)
(add-libs {'org.clojure/data.csv {:mvn/version "1.0.0"}})
(add-libs {'tick/tick {:mvn/version "0.4.27-alpha"}})
(add-libs {'thi.ng/geom {:mvn/version "1.0.0-RC4"}})
(add-libs {'thi.ng/math {:mvn/version "0.3.0"}})
(add-libs {'thi.ng/ndarray {:mvn/version "0.3.2"}})
(add-libs {'thi.ng/color {:mvn/version "1.4.0"}})
(add-libs {'factual/geo {:mvn/version "3.0.1"}})
(add-libs {'com.github.jai-imageio/jai-imageio-core {:mvn/version "1.4.0"}})
(add-libs {'clj-curl/clj-curl {:mvn/version "1.1.2"}})
(add-libs {'guru.nidi.com.kitfox/svgSalamander {:mvn/version "1.1.3"}})
(add-libs {'batik-rasterize/batik-rasterize {:mvn/version "0.1.2"}
           'xerces/xerces {:mvn/version  "2.4.0"}
           'org.apache.xmlgraphics/batik-transcoder {:mvn/version  "1.14"}
           'org.apache.xmlgraphics/batik-codec {:mvn/version  "1.10"}
           'org.apache.xmlgraphics/batik-anim {:mvn/version  "1.10"}
           'org.apache.xmlgraphics/xmlgraphics-commons {:mvn/version  "2.3"}})

(require '[clojure.java.io :as io]
         '[clojure.data.csv :as csv]
         '[tick.alpha.api :as tick]
         '(thi.ng.geom [core :as geom]
                       [matrix :as matrix])
         '[thi.ng.geom.viz.core :as viz]
         '[thi.ng.geom.svg.core :as svg]
         '[thi.ng.math.core :as math]
         '[thi.ng.ndarray.core :as ndarray]
         '[thi.ng.color.core :as col]
         '[thi.ng.math.noise :as noise]
         '[clj-curl.easy :as curl-easy]
         '[clj-curl.opts :as curl-opts]
         '[batik.rasterize :as batik])
(use 'geo.io)
(use 'geo.jts)


;; Our data comes in he following format
#_(->> "/home/geokon/Data/EventX.csv"
       io/reader
       csv/read-csv
       (take 10))
;;
;; => (["USB-500/600 Log File" ""]
;;     ["Name: EventX" ""]
;;     ["Model: USB-505" ""]
;;     ["Serial Number: 2506" ""]
;;     ["Index"
;;      "Date/Time"
;;      "Event"
;;      "Input: Open/Close Contact (Contact Closing)"
;;      ""]
;;     ["1" "2022-01-17 5:51:41.7 PM" "Contact Closing" ""]
;;     ["2" "2022-01-17 5:56:01.4 PM" "Contact Closing" ""]
;;     ["3" "2022-01-17 6:03:58.4 PM" "Contact Closing" ""]
;;     ["4" "2022-01-17 6:47:43.9 PM" "Contact Closing" ""]
;;     ["5" "2022-01-17 7:40:14.9 PM" "Contact Closing" ""])

(defn loggertime-to-iso8601
  "Convert the logger's time format to iso8601.
  Given a str returns a str.
  Can optionally be given a UTC offset (as a string)
  By default it's '+00:00'
  Ex:
  '2022-01-17 5:51:41.7 PM'
  becomes
  '2022-01-17T17:56:01+00:00'
  Note: the seconds get rounded off unfortunately
  Note2: For Taipei use +08:00
  "
  ([logger-str]
   (loggertime-to-iso8601 "+00:00"
                          logger-str))
  ([offset-str
    logger-str]
   (let [[date
         time
         am-or-pm](clojure.string/split logger-str
                                        #"\s")
        [hours
         minutes
         seconds] (map #(int(Double/parseDouble %))
                       (clojure.string/split time
                                             #":"))
         pm-offset (if (and (= am-or-pm "PM")
                            (not= hours 12)) ;; 12PM is noon
                    12
                    (if (and (= am-or-pm "AM")
                             (= hours 12))
                      -12 ;;12AM is 0
                      0))]
    (str (tick/date date)
         "T"
         (->> hours
              (+ pm-offset) ;; to 24 hr format
              (format "%02d"))
         ":"
         (->> minutes
              (format "%02d"))
         ":"
         (->> seconds
              (format "%02d"))
         offset-str))))


#_(->> event-str-times
       first)
;; => "2022-01-17 5:51:41.7 PM"
#_(->> event-str-times
       first
       (loggertime-to-iso8601 "+08:00")
       tick/offset-date-time)
;; => #time/offset-date-time "2022-01-17T17:51:42+08:00"

(def event-tick-times  (->> "/home/geokon/Data/EventX.csv"
                            io/reader
                            csv/read-csv
                            (drop 5) ;; remove header
                            (map second) ;; get second column
                            (mapv (partial loggertime-to-iso8601
                                           "+08:00")) ;; convert time strings
                            (mapv tick/offset-date-time))) ;; load into tick objects

#_(->> event-tick-times
       (take 5))
;; => (#time/offset-date-time "2022-01-17T17:51:42+08:00"
;;     #time/offset-date-time "2022-01-17T17:56:01+08:00"
;;     #time/offset-date-time "2022-01-17T18:03:58+08:00"
;;     #time/offset-date-time "2022-01-17T18:47:44+08:00"
;;     #time/offset-date-time "2022-01-17T19:40:15+08:00")

(def time-between-ticks
  "The inverse will be the average rain rate"
  (->> event-tick-times
       (partition 2 1 )
       (map #(tick/seconds (tick/between (first %)
                                         (second %))))))

(def rain-rate-between-ticks
  "The Y axis of sorts
  The rain amount is fixed to 0.3mm of rain.
  The longer the duration between ticks the lower the rate"
  (->> time-between-ticks
       (mapv #(/ 0.3
                 (+ % ;; add delta to not divide by zero
                    0.1)))))

(def time-from-first-tick
  "The X axis of sorts"
  (mapv #(tick/seconds (tick/between (first event-tick-times)
                                     %))
        event-tick-times))

(def time-rate-pairs
  "The inverse will be the average rain rate"
  (->> time-from-first-tick
       (partition 2 1 )
       (mapcat #(vector [(first %2) %1]
                      [(second %2) %1])
             rain-rate-between-ticks)))

(defn mm-plot
  [width
   height
   left-margin
   data]
  (-> {:x-axis (viz/linear-axis
		{:domain [0
                          (-> data
                              last
                              first)]
		 :range  [left-margin width]
		 :minor  30
		 :label-style {:fill "none"}
		 :pos    height})
       :y-axis (-> (viz/linear-axis {:domain  [-0.0001
                                               0.01]
		                 :range   [height 0]
		                 :major  100
		                 :minor  10
		                 :pos    left-margin})
                   (assoc :major [100]))
       :grid {:minor-x true
	      :minor-y false}
       :data [{:values data
	       :attribs {:fill "none" :stroke "red"}
	       :layout  viz/svg-line-plot}]}
      (viz/svg-plot2d-cartesian)))

(->> time-rate-pairs
     (mm-plot 200
              100
              10)
     (spit "gauge.svg"))
