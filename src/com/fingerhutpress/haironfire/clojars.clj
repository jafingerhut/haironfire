(ns com.fingerhutpress.haironfire.clojars
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn read-clojars-feed [rdr]
  (let [sentinel-obj (Object.)]
    (loop [artifacts (transient [])]
      (let [x (edn/read {:eof sentinel-obj} rdr)]
        (if (identical? x sentinel-obj)
          (persistent! artifacts)
          (recur (conj! artifacts x)))))))


(comment

(do
(import '(java.io PushbackReader))
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[clojure.set :as set])
(require '[clojure.string :as str])
(require '[com.fingerhutpress.haironfire.clojars :as cloj])
)
;; end of do

(def r1 (PushbackReader. (io/reader "feed.clj")))
(def as (cloj/read-clojars-feed r1))
(count as)

(defn sorted-key-set [my-map]
  (into (sorted-set) (keys my-map)))

(def fs (frequencies (map sorted-key-set as)))
(count fs)
(pprint fs)
(def all-keys (apply set/union (keys fs)))
all-keys
;; This is the complete set of keys that appear in at least one map:

;; :artifact-id
;; :description
;; :group-id
;; :homepage
;; :scm
;; :url
;; :versions

(def kfreqs (let [n (count as)]
              (for [k all-keys]
                (let [c (count (filter #(contains? % k) as))]
                  [k {:count c, :fraction (format "%.2f%%"
                                                  (* 100.0 (/ (double c) n)))}]))))
(pprint kfreqs)
;; ([:artifact-id {:count 25675, :fraction "100.00%"}]
;;  [:description {:count 23954, :fraction "93.30%"}]
;;  [:group-id {:count 25675, :fraction "100.00%"}]
;;  [:homepage {:count 19637, :fraction "76.48%"}]
;;  [:scm {:count 21171, :fraction "82.46%"}]
;;  [:url {:count 19637, :fraction "76.48%"}]
;;  [:versions {:count 25675, :fraction "100.00%"}])

(def scmtypefreqs (frequencies (map #(type (:scm %)) as)))
scmtypefreqs
;; {clojure.lang.PersistentArrayMap 21171, nil 4504}

(def scmkeyfreqs (frequencies (map #(sorted-key-set (get % :scm {})) as)))
(pprint scmkeyfreqs)

(def scm-by-keys (group-by #(sorted-key-set (get % :scm {})) as))
(count scm-by-keys)
(pprint (keys scm-by-keys))

(pprint (take 10 (map :artifact-id (scm-by-keys #{:tag}))))

;; Hand-selected sample of some artifacts with only key :tag in the
;; map that is the value of the :scm key:

;; {:group-id "clojure-interop", :artifact-id "javax.swing", :description "Clojure to Java Interop Bindings for javax.swing", :scm {:tag "HEAD"}, :homepage "https://github.com/clojure-interop/java-jdk", :url "https://github.com/clojure-interop/java-jdk", :versions ["1.0.5" "1.0.4" "1.0.3" "1.0.2" "1.0.0"]}

(defn artifact-source-loc [art-map]
  (if (contains? art-map :scm)
    ;; then contains key :scm
    (let [scm (art-map :scm)]
      (if (contains? scm :connection)
        (let [scm-conn (scm :connection)]
          (cond
            (not (string? scm-conn))
            {:scm-connection-non-string-class (class scm-conn)}

            (str/starts-with? scm-conn "scm:git:git://github.com")
            {:scm-connection-github true}

            (str/starts-with? scm-conn "scm:git:git://")
            {:scm-connection-git-not-github true}

            (str/starts-with? scm-conn "scm:git:")
            {:scm-connection-git-then-non-git true}

            (str/starts-with? scm-conn "scm:")
            {:scm-connection-not-git true}

            :else
            {:scm-connection-not-scm true}))
        ;; else no :connection key inside :scm map
        (if (contains? scm :url)
          {:scm-no-connection-but-yes-url true}
          {:scm-no-connection-and-no-url true})))
    ;; else does not contain key :scm
    ;;{:no-scm true}
    (if (contains? art-map :url)
      (let [url (art-map :url)]
        (cond
          (not (string? url))
          {:url-non-string-class (class url)}
          
          (or (str/starts-with? url "http://github.com")
              (str/starts-with? url "http://www.github.com")
              (str/starts-with? url "https://github.com")
              (str/starts-with? url "https://www.github.com"))
          {:url-http-or-https-github true}
          
          (str/starts-with? url "http://example.com/FIXME")
          {:url-http-example-fixme true}

          (or (str/starts-with? url "http://")
              (str/starts-with? url "https://"))
          {:url-http-or-https-not-github true}

          (= url "")
          {:url-empty-string true}

          :else
          {:url-not-http true}))
      ;; else
      {:no-scm-and-no-url true})))

(def scmtypefreqs (frequencies (map #(type (:scm %)) as)))

(def srclocs (frequencies (map artifact-source-loc as)))
(count srclocs)
(pprint srclocs)

(def srclocs2 (group-by artifact-source-loc as))
(count srclocs2)
(pprint (take 5 (srclocs2 {:scm-connection-git-not-github true})))
(pprint (take 5 (srclocs2 {:scm-connection-not-git true})))
(pprint (take 5 (srclocs2 {:scm-connection-not-scm true})))
(pprint (take 5 (srclocs2 {:scm-connection-git-then-non-git true})))
(pprint (take 5 (srclocs2 {:scm-no-connection-and-no-url true})))
(pprint (take 5 (srclocs2 {:scm-no-connection-but-yes-url true})))
(pprint (take 10 (srclocs2 {:no-scm true})))

(pprint (take 10 (srclocs2 {:url-http-or-https-not-github true})))

(pprint (take 10 (srclocs2 {:url-not-http true})))

)
