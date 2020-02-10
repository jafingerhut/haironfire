(ns com.fingerhutpress.haironfire.clojars
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn http-url-string? [string]
  (or (str/starts-with? string "http://")
      (str/starts-with? string "https://")))

(defn github-http-url-string? [string]
  (or (str/starts-with? string "http://github.com")
      (str/starts-with? string "http://www.github.com")
      (str/starts-with? string "https://github.com")
      (str/starts-with? string "https://www.github.com")))

(defn artifact-source-loc
  "Check the data about on Clojars artifact to see if it looks like it
  has information about a source code repository that the artifact was
  created from, and if so, whether it is Git or some other revision
  control system, and if Git, whether it is on github.com or some
  other web site."
  [art-map]
  (if (contains? art-map :scm)
    (let [scm (art-map :scm)]
      (if (contains? scm :connection)
        (let [scm-conn (scm :connection)]
          (cond
            (not (string? scm-conn))
            {:artifact-loc-type :scm-connection-non-string-class,
             :class (class scm-conn)}

            (str/starts-with? scm-conn "scm:git:git://github.com")
            {:artifact-loc-type :scm-connection-github
             :source-url (subs scm-conn (count "scm:git:"))
             :maven-scm-provider "git"}

            (str/starts-with? scm-conn "scm:git:git://")
            {:artifact-loc-type :scm-connection-git-not-github
             :source-url (subs scm-conn (count "scm:git:"))
             :maven-scm-provider "git"}

            (str/starts-with? scm-conn "scm:git:")
            {:artifact-loc-type :scm-connection-git-then-non-git
             :source-url-maybe (subs scm-conn (count "scm:git:"))
             :maven-scm-provider "git"}

            (str/starts-with? scm-conn "scm:")
            (merge {:artifact-loc-type :scm-connection-not-git}
                   (let [provider (re-find #"^scm:([^:]+):" scm-conn)]
                     (if provider
                       {:maven-scm-provider provider})))

            ;; Apparently some projects have a :connection key whose
            ;; value is a string that looks like a Github URL starting
            ;; with "http:" or "https:" instead of "scm:git:".
            ;; Recognize those few specially here.
            (github-http-url-string? scm-conn)
            {:artifact-loc-type :scm-connection-http-github-url
             :source-url scm-conn}
            
            :else
            {:artifact-loc-type :scm-connection-not-scm}))
        ;; else no :connection key inside :scm map
        (if (contains? scm :url)
          (let [scm-url (scm :url)]
            (merge
             {:artifact-loc-type :scm-no-connection-and-yes-url}
             (cond
               (github-http-url-string? scm-url)
               {:artifact-loc-type :scm-no-connection-and-github-url
                :source-url scm-url}
               
               (str/starts-with? scm-url "http://example.com/FIXME")
               {:artifact-loc-type :scm-no-connection-and-example-url}

               (http-url-string? scm-url)
               {:artifact-loc-type :scm-no-connection-and-non-github-url
                :source-url-maybe scm-url}

               (= scm-url "")
               {:artifact-loc-type :scm-no-connection-and-empty-url}
               
               :else
               {:artifact-loc-type :scm-no-connection-and-yes-url})))
          {:artifact-loc-type :scm-no-connection-and-no-url})))
    ;; else does not contain key :scm
    (if (contains? art-map :url)
      (let [url (art-map :url)]
        (cond
          (not (string? url))
          {:artifact-loc-type :url-non-string-class
           :class (class url)}
          
          (github-http-url-string? url)
          {:artifact-loc-type :url-http-or-https-github
           :source-url url}
          
          (str/starts-with? url "http://example.com/FIXME")
          {:artifact-loc-type :url-http-example-fixme}

          (http-url-string? url)
          {:artifact-loc-type :url-http-or-https-not-github
           :source-url-maybe url}

          (= url "")
          {:artifact-loc-type :url-empty-string}

          :else
          {:artifact-loc-type :url-not-http}))
      ;; else no :url key
      {:artifact-loc-type :no-scm-and-no-url})))

(defn read-clojars-feed [rdr]
  (let [sentinel-obj (Object.)]
    (loop [artifacts (transient [])]
      (let [x (edn/read {:eof sentinel-obj} rdr)]
        (if (identical? x sentinel-obj)
          (persistent! artifacts)
          (recur (conj! artifacts x)))))))

(defn pctg [n1 n2]
  (* 100.0 (/ (* 1.0 n1) n2)))

(defn summarize-clojars-feed-data
  "clojars-feed-data should be a sequence of maps as returned by
  read-clojars-feed, when reading a file containing data like that
  available at the URL http://clojars.org/repo/feed.clj.gz as of
  2020-Feb.

  It returns a map with the following keys and corresponding values:

  :summary-string

  A string giving some summary statistics, in human readable form,
  about the contents of the data.

  :data

  A sequence of maps that contain all of the information the input
  maps `m` do, plus some extra keys as returned by (artifact-source
  m)."
  [clojars-feed-data]
  (let [d (map #(merge % (artifact-source-loc %)) clojars-feed-data)
        n (count d)
        loc-types (into (sorted-map)
                        (frequencies (map :artifact-loc-type d)))
        num-source-url (count (filter :source-url d))
        num-source-url-maybe (count (filter :source-url-maybe d))
        remaining (- n num-source-url num-source-url-maybe)

        msg1 (format "%d artifacts where
%5d (%6.1f%%) appear to have usable URLs for accessing source code,
%5d (%6.1f%%) have URLs that are less likely to be usable, and
%5d (%6.1f%%) no URL likely to be usable at all was found."
                     n
                     num-source-url (pctg num-source-url n)
                     num-source-url-maybe (pctg num-source-url-maybe n)
                     remaining (pctg remaining n))
        msg2 (format
"Artifacts partitioned into sets based on what kind of SCM :connection
or :url key was found, or a :url key at the top level of the map:")

        msg3 (str/join "\n"
                       (for [[k cnt] loc-types]
                         (format "%5d (%6.1f%%) %s"
                                 cnt (pctg cnt n) (name k))))]

    {:data d,
     :string (str msg1 "\n\n" msg2 "\n" msg3)}))


(comment

(do
(import '(java.io PushbackReader))
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[clojure.set :as set])
(require '[clojure.string :as str])
(require '[com.fingerhutpress.haironfire.clojars :as cloj] :reload)
)
;; end of do

(def tmp (let [r1 (PushbackReader. (io/reader "feed.clj"))]
           (->> r1
                cloj/read-clojars-feed
                cloj/summarize-clojars-feed-data)))
(def as (:data tmp))
(print (:string tmp))
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

(def scmtypefreqs (frequencies (map #(type (:scm %)) as)))

(def srclocs (frequencies (map :artifact-loc-type as)))
(count srclocs)
(pprint (into (sorted-map) srclocs))
(count (filter :source-url as))
(count (filter :source-url-maybe as))

(def srclocs2 (group-by :artifact-loc-type as))
(count srclocs2)
(pprint (take 5 (srclocs2 :scm-connection-github)))
(pprint (take 5 (srclocs2 :scm-connection-git-not-github)))
(pprint (take 5 (srclocs2 :scm-connection-not-git)))
(pprint (take 5 (srclocs2 :scm-connection-not-scm)))
(pprint (take 5 (srclocs2 :scm-connection-git-then-non-git)))
(pprint (take 5 (srclocs2 :scm-no-connection-and-no-url)))
(pprint (take 10 (srclocs2 :scm-no-connection-and-yes-url)))
(pprint (take 10 (srclocs2 :scm-no-connection-and-non-github-url)))
(pprint (take 10 (srclocs2 :no-scm)))

(pprint (take 10 (srclocs2 :url-http-or-https-not-github)))

(pprint (take 10 (srclocs2 :url-not-http)))

)
