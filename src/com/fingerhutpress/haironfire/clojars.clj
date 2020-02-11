(ns com.fingerhutpress.haironfire.clojars
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [medley.core :as med]))

(defn duplicates
  "Return a map similar to (frequencies coll), except it only contains
  keys equal to values that occur at least twice in coll."
  [coll]
  (->> (frequencies coll)
       (med/filter-vals #(> %1 1))))

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

            (and (str/starts-with? scm-conn "scm:git:")
                 (str/ends-with? scm-conn "/.git"))
            {:artifact-loc-type :scm-connection-git-but-missing-project-name
             :maven-scm-provider "git"}

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

;; I have found that as of 2020-Feb on Clojars feed.clj.gz file about
;; artifacts, none of them contain the ":" character in the group-id
;; string, nor in the artifact-id string.  It is a legal character in
;; a Unix/Linux file name, so I will use it as a separator between
;; them when creating some file names.

(def group-id-artifact-id-separator ":")

(defn group-artifact-id
  [art-map]
  (str (:group-id art-map)
       group-id-artifact-id-separator
       (:artifact-id art-map)))

;; I have found that there are many groups of artifacts listed on
;; Clojars.org in the feed.clj.gz file that have the same Github.com
;; URL for source code.  When retrieving source code, I would like to
;; get at most one copy of each Github repository, to save download
;; time, but also disk space, as there are some not-small-disk-space
;; projects that are represented over 10 times each in feed.clj.gz,
;; probably because a single Git repository contains the code for
;; things released as multiple different JAR files on Clojars.

;; Perhaps later when doing statistics on projects, it might be nice
;; to use download counts for each Clojars.org artifact, but save that
;; issue for a bi later.

;; I want to download the git project exactly once when this happens.
;; Pick whichever one happens to be first in my data with the same
;; URL.

;; The function add-canonical-artifact-ids helps to pick a 'canonical'
;; artifact for each value of :source-url.

(defn add-canonical-artifact-ids
  "Given a sequence of maps describing Clojars artifacts, each map
  containing:

  + at least the keys :group-id and :artifact-id
  + optionally a key :source-url with an associated string value

  Return a sequence of maps with the same key/value pairs, except the
  ones that have a :source-url key in the input will have the
  key :canonical-artifact added to them.  The value of that key will
  itself be a map containing keys :group-id and :artifact-id.

  All maps in the sequence that have the same value associated with
  the :source-url key will have the same value associated with
  the :canonical-artifact key in the returned maps, and its value will
  be the :group-id and :artifact-id of one arbitrarily chosen artifact
  that had the same :source-url."
  [art-maps]
  (let [art-maps-by-source-url (group-by :source-url
                                         (filter :source-url art-maps))]
    (map (fn [art-map]
           (if (contains? art-map :source-url)
             (assoc art-map :canonical-artifact
                    (select-keys (first (art-maps-by-source-url
                                         (art-map :source-url)))
                                 [:group-id :artifact-id]))
             ;; else
             art-map))
         art-maps)))

(defn canonical-artifact? [art-map]
  (and (contains? art-map :canonical-artifact)
       (let [can (:canonical-artifact art-map)]
         (and (= (:group-id art-map) (:group-id can))
              (= (:artifact-id art-map) (:artifact-id can))))))

(defn read-clojars-feed [rdr]
  (let [sentinel-obj (Object.)]
    (loop [artifacts (transient [])]
      (let [x (edn/read {:eof sentinel-obj} rdr)]
        (if (identical? x sentinel-obj)
          (persistent! artifacts)
          (recur (conj! artifacts x)))))))

(defn clean-artifact?
  [art-map]
  (and (contains? art-map :group-id)
       (string? (art-map :group-id))
       (not (str/includes? (art-map :group-id)
                           group-id-artifact-id-separator))
       (contains? art-map :artifact-id)
       (string? (art-map :artifact-id))
       (not (str/includes? (art-map :artifact-id)
                           group-id-artifact-id-separator))))

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
  (let [n1 (count clojars-feed-data)
        d1 (filter clean-artifact? clojars-feed-data)

        d (map #(merge % (artifact-source-loc %)) d1)
        n (count d)
        loc-types (into (sorted-map)
                        (frequencies (map :artifact-loc-type d)))
        num-source-url (count (filter :source-url d))
        num-source-url-maybe (count (filter :source-url-maybe d))
        remaining (- n num-source-url num-source-url-maybe)

        group-artifact-ids (map group-artifact-id d)
        dup-group-artifact-ids (duplicates group-artifact-ids)
        ndups (reduce + (vals dup-group-artifact-ids))

        msg1 (format "%d artifacts, where
%d remain after removing ones with 'unclean' data, out of which:

%5d (%6.1f%%) appear to have usable URLs for accessing source code,
%5d (%6.1f%%) have URLs that are less likely to be usable, and
%5d (%6.1f%%) no URL likely to be usable at all was found.

%5d (%6.1f%%) have unique (group-id:artifact-id) pair
%5d (%6.1f%%) have (group-id:artifact-id) pair that is duplicate of another"
                     n1
                     n
                     num-source-url (pctg num-source-url n)
                     num-source-url-maybe (pctg num-source-url-maybe n)
                     remaining (pctg remaining n)
                     (- n ndups) (pctg (- n ndups) n)
                     ndups (pctg ndups n))
        msg2 (format
"Artifacts partitioned into sets based on what kind of SCM :connection
or :url key was found, or a :url key at the top level of the map:")

        msg3 (str/join "\n"
                       (for [[k cnt] loc-types]
                         (format "%5d (%6.1f%%) %s"
                                 cnt (pctg cnt n) (name k))))
        ]

    {:data d,
     :string (str msg1 "\n\n" msg2 "\n" msg3)}))

;; Note 1: On this StackOverflow question:

;; https://serverfault.com/questions/544156/git-clone-fail-instead-of-prompting-for-credentials

;; I learned of the technique of setting the environment variable
;; GIT_TERMINAL_PROMPT to 0 to force 'git clone' to fail if it would
;; normally try to ask you questions, such as entering account name
;; and password, at the terminal.  I want getting any such git
;; repository to fail rather than hanging, waiting for a response.

(defn write-git-clone-bash-script [bash-fname base-dir-name art-maps]
  (with-open [wrtr (io/writer bash-fname)]
    (binding [*out* wrtr]
      (println "#! /bin/bash")
      (println)
      ;; See Note 1
      (println "export GIT_TERMINAL_PROMPT=0")
      (println "mkdir -p " base-dir-name)
      (doseq [art-map (filter :source-url art-maps)]
        (let [ga-id (group-artifact-id art-map)]
          (println)
          (println "# " ga-id)
          (if (canonical-artifact? art-map)
            (do
              (println "cd " base-dir-name)
              (println "mkdir -p " ga-id)
              (println "cd " ga-id)
              (println "echo " (:source-url art-map) " > url.txt")
              (println "git clone --recursive " (:source-url art-map))
              (println "exit_status=$?")
              (println "echo $exit_status > exit_status.txt"))
            ;; else
            (println "# canonical artifact has :group-id"
                     (:group-id (:canonical-artifact art-map))
                     " :artifact-id"
                     (:artifact-id (:canonical-artifact art-map)))))))))


(comment

(do
(import '(java.io PushbackReader))
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[clojure.set :as set])
(require '[clojure.string :as str])
(require '[com.fingerhutpress.haironfire.clojars :as cloj] :reload)
(require '[medley.core :as med])
)
;; end of do

(def tmp (let [r1 (PushbackReader. (io/reader "feed.clj"))]
           (->> r1
                cloj/read-clojars-feed
                cloj/summarize-clojars-feed-data)))
;; as is short for 'artifacts'
(def as (cloj/add-canonical-artifact-ids (:data tmp)))
(print (:string tmp))
(count as)

(cloj/write-git-clone-bash-script "get-artifact-source2.sh"
                                  "/home/andy/clj/haironfire/repos"
                                  as)


(count as)
(def as-by-source-url (group-by :source-url (filter :source-url as)))
(count as-by-source-url)

(def as2 (cloj/add-canonical-artifact-ids as))
(count as2)
(def as3 (filter #(= (:source-url %) "http://www.github.com/zcaudate/vinyasa") as2))
(count as3)
(pprint (take 3 as3))

(->> as (filter :source-url) (map :source-url) (take 100) pprint)

(->> as
     (filter :source-url)
     (map :source-url)
     (filter #(str/includes? % "github.com//.git"))
     (take 20)
     pprint)

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
