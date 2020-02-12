(ns com.fingerhutpress.haironfire.clojars
  (:import (java.io PushbackReader))
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.reader.edn :as tredn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [medley.core :as med]
            [clojure.data.priority-map :as pm]))

(set! *warn-on-reflection* true)

(defn duplicates
  "Return a map similar to (frequencies coll), except it only contains
  keys equal to values that occur at least twice in coll."
  [coll]
  (->> (frequencies coll)
       (med/filter-vals #(> %1 1))))

(defn reverse-cmp [a b]
  (compare b a))

(defn group-by->freqs
  "Given a map that is similar to the return value of group-by,
  i.e. it is a map, where the values are sequences of values
  associated with that key, return a map with the same keys, where the
  values are the lengths of the sequences in the input map.  The
  returned map is a priority-map, where key/value pairs are sorted by
  counts, from largest count to smallest count, which makes it nicer
  for printing the value and seeing the most common keys first."
  [group-by-result-map]
  (into (pm/priority-map-by reverse-cmp)
        (med/map-vals count group-by-result-map)))

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

(let [sentinel-obj (Object.)]
  (defn edn-read-all-forms [rdr edn-read-fn]
    (loop [forms (transient [])]
      (let [x (edn-read-fn {:eof sentinel-obj} rdr)]
        (if (identical? x sentinel-obj)
          (persistent! forms)
          (recur (conj! forms x)))))))

(defn read-clojars-feed [rdr edn-read-fn]
  (edn-read-all-forms rdr edn-read-fn))

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

(defn file-properties [^java.io.File f]
  {:file f
   ;; full path name of file as string
   :path (. f (getPath))
   ;; base name (i.e. last component of full path name, without any of
   ;; the containing directory names.
   :name (. f (getName))
   :directory? (. f (isDirectory))
   :regular? (. f (isFile))})

(defn files-in-directory
  "Returns a sequence of java.io.File objects for all files directly
  inside of the directory `dir`, without looking for files within any
  subdirectories.  On Linux, does not return any elements for the
  special directories '.' or '..'"
  [^java.io.File dir]
  (seq (. dir (listFiles))))

(defn exit-status-from-file
  "Given a java.io.File f, open it for reading and read the first
  line.  If it contains nothing but a consecutive sequence of decimal
  digits, with optional leading and trailing white space, convert that
  to a number and return it.  Otherwise return nil.  Throws exception
  if the file does not exist, or cannot be opened for reading."
  [^java.io.File f]
  (with-open [rdr (io/reader f)]
    (let [line1 (first (line-seq rdr))
          [match? digit-str] (if line1 (re-matches #"\s*(\d+)\s*" line1))]
      (if match?
        (Long/parseLong digit-str)))))

(defn find-regular-files
  "Given a set of base file names as strings (i.e. without any path),
  and a sequence of maps with file properties, e.g. perhaps one
  returned by the function file-properties, return a map where the
  keys are a subset of name-set, and the corresponding values are
  sequences containing the elements of file-props-seq for all regular
  files in file-props-seq that have that base name."
  [name-set file-props-seq]
  (dissoc (group-by (fn reg-file-with-name [file-props]
                      (if (:regular? file-props)
                        (if-let [fname (name-set (:name file-props))]
                          fname)))
                    file-props-seq)
          nil))

(defn one-project-retrieved-info
  "Given a directory `one-repo-dir`, check that it appears to have
  been created by a bash script as created by the function
  write-git-clone-bash-script, for one Clojars artifact, with source
  code retrieved from a single git repository.

  Such a directory should have at least one regular file named
  `exit_status.txt`, readable, containing a single decimal integer in
  ASCII, which is the exit status of a 'git clone' command executed
  earlier from the directory containing that file.

  It should also contain a url.txt regular file, but we will ignore
  that here.

  There should also be exactly one directory, which the 'git clone'
  command created if it was successful.  That directory should contain
  a directory named `.git`, and one should be able to successfully run
  a `git status .` command in there, or most other git commands that
  only read the file system, without any errors.

  The return value is a map that always contains the keys:

  :repo-dir java.io.File that is the argument `one-repo-dir`

  :group-id - string containing the group-id part of the directory
  name, before the colon character.

  :artifact-id - string containing the artifact-id part of the
  directory name, after the colon character.

  :error - see below

  If any of the things described above are not true, return a map with
  key :error having the value `true`, and a key :description whose
  value is a string describing the problem found.

  If all of the things described above are true, return a map with
  key :error having the value `false`.

  The map will also contain a key :tooling-files whose value is a map
  as described below.

  Check if the root directory of the git clone contains any or all of
  files with these names.  The map describing them has strings of the
  file names that are present as keys, where the corresponding values
  containing information about that file as returned by the function
  `file-properties`.

      project.clj pom.xml build.boot deps.edn"
  [^java.io.File one-repo-dir]
  (let [file-props (map file-properties (files-in-directory one-repo-dir))
        exit-status-files (filter #(= "exit_status.txt" (:name %)) file-props)
        dirs (filter :directory? file-props)
        one-repo-dir-name (:name (file-properties one-repo-dir))
        [group-id artifact-id] (str/split one-repo-dir-name #":")
        ret-map {:repo-dir one-repo-dir,
                 :group-id group-id
                 :artifact-id artifact-id}]
    (cond
      (not= 1 (count exit-status-files))
      (merge ret-map
             {:error true,
              :description "Should be exactly 1 file named exit_status.txt"})

      (not= 1 (count dirs))
      (merge ret-map {:error true,
                      :description "Should be exactly 1 sub-directory"})

      :else
      (let [exit-status-file (first exit-status-files)
            exit-status (exit-status-from-file (:file exit-status-file))]
        (if-not (zero? exit-status)
          (merge ret-map {:error true,
                          :description
                          (str "File exit_status.txt should contain"
                               " number 0 but found " exit-status)})
          ;; else exist-status is 0
          (let [root-dir (:file (first dirs))
                root-dir-files (map file-properties
                                    (files-in-directory root-dir))
                dot-git-dirs (filter #(and (:directory? %) (= ".git" (:name %)))
                                     root-dir-files)
                fname-set #{"project.clj" "pom.xml" "build.boot" "deps.edn"}
                reg-files (find-regular-files fname-set root-dir-files)]
            (cond
              (not= 1 (count dot-git-dirs))
              (merge ret-map
                     {:error true,
                      :description (str "git clone directory should contain"
                                        " exactly 1 directory named .git")})

              (some #(> (count (reg-files %)) 1) fname-set)
              (merge ret-map
                     {:error true,
                      :description (str "git clone directory contained more"
                                        " than 1 file with name: "
                                        (str/join ", " (seq fname-set)))})

              :else
              (merge ret-map
                     {:error false,
                      :tooling-files (med/map-vals first reg-files)}))))))))

(defn projects-retrieved [^String repos-dir-abs-path]
  (let [repos-dir (java.io.File. repos-dir-abs-path)
        possible-repo-dirs (files-in-directory repos-dir)]
    (map one-project-retrieved-info possible-repo-dirs)))

(defn defproject-form?
  "Return {:error false :data d} if `form` appears to be a Leiningen
  defproject form, where d is a Clojure map.  The keys of d are the
  (nth form 3), (nth form 5), etc. elements of the list `form`.  The
  value associated with key (nth form 3) is (nth form 4), the value
  associated with the key (nth form 5) is (nth form 6), etc.

  Return {:error true, :description msg} if something is found that
  makes `form` appear not to be a defproject form, where `msg` is a
  string describing the issue."
  [form]
  (cond
    ;; Note: in the ~12,000 project.clj files I have downloaded to
    ;; check, there are 3 that clojure.edn/read can read without
    ;; throwing an exception, that fail this check for the _first_
    ;; form read from the file, because they contain what looks like a
    ;; spurious "1" or "=" character before the "(defproject ...)"
    ;; form.  Fun!  This doesn't seem to bother Leiningen, because it
    ;; reads and evals all forms in the file, and = and 1 are both
    ;; valid Clojure forms that evaluate with no errors, and have no
    ;; side effects when evaluating them.
    (not (list? form))
    {:error true, :description "Not a list"}
    
    (not= 'defproject (first form))
    {:error true, :description "List does not start with symbol 'defproject'"}

    (< (count form) 3)
    {:error true, :description "defproject list has fewer than 3 elements"}

    (not (symbol? (nth form 1)))
    {:error true, :description "defproject list second element should be symbol"}

    ;; Commenting this out, because I found 28 projects that have a
    ;; project.clj file where instead of a string, there was a Clojure
    ;; form that evaluated to a version string, used by the project
    ;; maintainer to get a version string from an environment variable
    ;; in many cases.  As long as we do not need the actual version
    ;; string, there is no need to exclude such project.clj files for
    ;; my purposes here.
    ;;(not (string? (nth form 2)))
    ;;{:error true, :description "defproject list third element should be string"}
    
    (even? (count form))
    {:error true, :description "defproject list must have odd number of elements"}

    :else
    (let [ks (take-nth 2 (drop 3 form))
          vs (take-nth 2 (drop 4 form))
          non-keywords (remove keyword? ks)]
      (cond
        (seq non-keywords)
        {:error true,
         :description (str "defproject list had non-keywords where keyword"
                           " expected, first being" (first non-keywords))}
        
        :else
        {:error false, :data (zipmap ks vs)}))))

(defn project-clj-file-info
  "Given the name of what should be a Leiningen project.clj file,
  attempt to read all of its forms using
  edn-read-fn (e.g. clojure.edn/read).  If an exception occurs while
  trying to do this, e.g. because the file contains some Clojure code
  constructs that are not valid EDN, return a map with the
  key :exception where its associated value is the exception that was
  thrown.

  If no exception is thrown while reading it, return a map with
  no :exception key, but instead has the following keys and
  corresponding values:

  :forms - A vector containing the sequence of values read from the
  file.

  :first-form-defproject? - true if at least one form was read, and
  the first form appears to be a Leiningen defproject form, according
  to the return value of the function defproject-form?  Otherwise
  false.

  :defproject-form-info - A map as returned by defproject-form? when
  called on the first form read from the file, or {:error
  true :decription \"No forms found\"} if no values were found in the
  file."
  [fname edn-read-fn]
  (with-open [rdr (PushbackReader. (io/reader fname))]
    (let [[exc forms] (try
                        [nil (edn-read-all-forms rdr edn-read-fn)]
                        (catch Exception e
                          [e nil]))
          ret (cond
                exc nil
                (zero? (count forms)) {:error true,
                                       :description "No forms found"}
                :else (defproject-form? (first forms)))
          first-form-defproject? (if exc false (not (:error ret)))]
      (if exc
        {:filename fname
         :exception exc}
        ;; else
        {:filename fname
         :forms forms
         :first-form-defproject? first-form-defproject?
         :defproject-form-info ret}))))

(defn my-exc-cause [projinfo]
  (:cause (Throwable->map (:exception projinfo))))

(defn good-one-lein-dep? [one-dep-vec]
  (cond
    (< (count one-dep-vec) 2)
    {:error true,
     :description (str "One :dependencies element was a vector with "
                       (count one-dep-vec) " elements, not at least 2 as expected: "
                       one-dep-vec)}

    (not (symbol? (one-dep-vec 0)))
    {:error true,
     :description (str "One :dependencies element was a vector with "
                       "a non-symbol " (one-dep-vec 0) " as first element,"
                       " with type " (class (one-dep-vec 0)))}

    (not (string? (one-dep-vec 1)))
    {:error true,
     :description (str "One :dependencies element was a vector with "
                       "a non-string " (one-dep-vec 1) " as second element,"
                       " with type " (class (one-dep-vec 1)))}
    
    (str/includes? (name (one-dep-vec 0)) "'")
    {:error true,
     :description (str "One :dependencies element was a vector with "
                       "a symbol " (one-dep-vec 0) " as first element,"
                       " containing a ' character in it, which is highly"
                       " suspect given we are reading project.clj files using"
                       " clojure.edn/read")}
    
    :else
    {:error false}))

(comment

(def sym1 (symbol "foo'bar"))
(name sym1)
(str/includes? (name sym1) "'")

(good-one-lein-dep? [])
(good-one-lein-dep? [1])
(good-one-lein-dep? [1 2])
(good-one-lein-dep? ['a 2])
(good-one-lein-dep? ['a "foo"])
(good-one-lein-dep? ['a'b "foo"])
(good-one-lein-dep? [(clojure.edn/read-string "'ab") "foo"])

)

(defn good-lein-deps? [defproject-form-info]
  (assert (false? (:error defproject-form-info)))
  (let [d (:data defproject-form-info)]
    (if (contains? d :dependencies)
      (let [deps (:dependencies d)]
        (cond
          ;; It is a vector in most project.clj files, but I have seen
          ;; a handful that use a list of vectors, or a map.  It seems
          ;; that as long as when you call seq on it, you get back a
          ;; sequnce of vectors (including MapEntry for seq'ing over a
          ;; map), Leiningen works with it.
          (not (or (vector? deps) (list? deps) (map? deps)))
          {:error true
           :description (str ":dependencies value is neither a vector,"
                             " a list, nor a map")}

          (not (every? vector? deps))
          {:error true
           :description ":dependencies value elements not all vectors"}

          :else
          (let [dep-problems (->> deps
                                  (map good-one-lein-dep?)
                                  (remove #(= {:error false} %)))]
            (if (first dep-problems)
              (first dep-problems)
              {:error false}))))
      ;; else no :dependencies key in defproject form.  As far as I
      ;; can tell, this is functionally equivalent to having an empty
      ;; list of dependencies, and is perfectly OK.  I found almost
      ;; 1,000 project.clj files out of 12,000 that had no top
      ;; level :dependencies key.  Many of them did have one or more
      ;; profiles with :dependencies keys, the :dev profile being a
      ;; common one.
      {:error false})))

(defn categorize-lein-projects [project-maps edn-read-fn]
  (let [project-clj-fnames (map #(:path ((:tooling-files %) "project.clj"))
                                project-maps)
        projinfos (mapv #(project-clj-file-info % edn-read-fn)
                        project-clj-fnames)
        ;; Divide them into ones that caused exceptions while reading
        ;; the project.clj file (excs), those that had a defproject
        ;; form that looks good first in the file (ok), and those that
        ;; had something other than a defproject form first in that
        ;; file (bad).
        excs (filter :exception projinfos)
        ok (filter :first-form-defproject? projinfos)
        bad (filter #(false? (:first-form-defproject? %)) projinfos)

        ;; Categorize the kinds of exceptions thrown while trying to
        ;; use clojure.edn/read to read the project.clj file.
        excs-grouped (group-by my-exc-cause excs)
        bad-grouped (group-by :defproject-form-info bad)

        ;; For the ones that look like they have a defproject form
        ;; first, examine their :dependencies value to see if it looks
        ;; good.
        okdeps (map (fn [pi]
                      (assoc pi :dependencies-check (good-lein-deps?
                                                     (:defproject-form-info pi))))
                    ok)
        ok-deps-ok (filter #(false? (get-in % [:dependencies-check :error]))
                          okdeps)
        ok-deps-bad (filter #(true? (get-in % [:dependencies-check :error]))
                            okdeps)

        ok-deps-bad-grps (group-by #(get-in % [:dependencies-check :description])
                                   ok-deps-bad)]

    {:project-maps project-maps
     :exceptions-during-edn-read excs
     :exceptions-during-edn-read-by-reason excs-grouped
     :defproject-not-first-or-failed-basic-checks bad
     :defproject-not-first-or-failed-basic-checks-by-reason bad-grouped
     :defproject-found-basic-checks-only okdeps
     :defproject-found-dependencies-problems ok-deps-bad
     :defproject-found-dependencies-problems-by-reason ok-deps-bad-grps
     :defproject-found-dependencies-ok ok-deps-ok}))

(defn print-lein-project-summary [lein-project-data]
  (let [n1 (count (:project-maps lein-project-data))
        n2 (count (:defproject-found-dependencies-ok lein-project-data))
        n3 (count (:exceptions-during-edn-read lein-project-data))
        n4 (count (:defproject-not-first-or-failed-basic-checks lein-project-data))
        n5 (count (:defproject-found-dependencies-problems lein-project-data))
        excs-grouped (:exceptions-during-edn-read-by-reason lein-project-data)
        bad-grouped (:defproject-not-first-or-failed-basic-checks-by-reason lein-project-data)
        ok-bad-deps-grouped (:defproject-found-dependencies-problems-by-reason lein-project-data)
        ]
    (println (format "%5d projects analyzed" n1))
    (println (format "%5d (%6.1f%%) EDN-readable project.clj files with well formed :dependencies (or no :dependencies key)"
                     n2 (pctg n2 n1)))
    (println (format "%5d (%6.1f%%) threw exceptions while trying to read as EDN (breakdown 1 below)"
                     n3 (pctg n3 n1)))
    (println (format "%5d (%6.1f%%) EDN-readable, but something other than defproject form read first (breakdown 2 below)"
                     n4 (pctg n4 n1)))
    (println (format "%5d (%6.1f%%) defproject form first, but :dependencies look ill formed (breakdown 3 below)"
                     n5 (pctg n5 n1)))
    (println)
    (println "Breakdown 1 of reasons for EDN reading of project.clj throwing exception:")
    (pp/pprint (group-by->freqs excs-grouped))
    (println)
    (println "Breakdown 2 of reasons first form doesn't appear to be a defproject:")
    (pp/pprint (group-by->freqs bad-grouped))
    (println)
    (println "Breakdown 3 of reasons defproject :dependencies value looks wrong, or more likely needs examination of :managed-dependencies in this or a different project to interpret:")
    (pp/pprint (group-by->freqs ok-bad-deps-grouped))))


(comment

(do
(import '(java.io PushbackReader))
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[clojure.tools.reader.edn :as tredn])
(require '[clojure.set :as set])
(require '[clojure.string :as str])
(require '[com.fingerhutpress.haironfire.clojars :as cloj] :reload)
(require '[medley.core :as med])
(require '[clojure.data.priority-map :as pm])
)
;; end of do

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BEGIN HERE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ... if you want to do the data collection and analysis from
;; scratch.

;; Use a web browser to download a copy of this file.  I say use a web
;; browser, because I could not figure out how to get the data back
;; using the `curl` command -- I only got back a redirect or error
;; response, but I got data about approximately 25,000 Clojars
;; artifacts in 2020-Feb when I used a web browser.

;; http://clojars.org/repo/feed.clj.gz

;; If your web browser does not automatically uncompress it for you,
;; use a command like `ungzip feed.clj.gz` to create the uncompressed
;; text file feed.clj

(def edn-read-fn clojure.edn/read)
#_(def edn-read-fn clojure.tools.reader.edn/read)
(def tmp (let [r1 (PushbackReader. (io/reader "feed.clj"))]
           (-> r1
               (cloj/read-clojars-feed edn-read-fn)
               cloj/summarize-clojars-feed-data)))
;; `as` is short for 'artifacts'
(def as (cloj/add-canonical-artifact-ids (:data tmp)))
(print (:string tmp))
(count as)

;; The next step writes the file get-artifact-source.sh and is pretty
;; quick.
(def repos-dir-abs-path "/home/andy/clj/haironfire/repos")
(cloj/write-git-clone-bash-script "get-artifact-source.sh" repos-dir-abs-path
                                  as)

;; See README section Data collected, and analysis results" for
;; details on how long running that bash script took on my system.  It
;; is by far the longest step of the process.


;; After downloading is complete, almost all of the remaining steps
;; are also pretty quick to complete, and read only a small subset of
;; the files that were created on the file system while executing that
;; script.


;; Assuming the kinds of files left behind by the
;; get-artifact-source.sh script, collect a list of successfully
;; retrieved projects, and for each one, which of them have the
;; following file names in their root directories:

;; project.clj pom.xml build.boot deps.edn

(def repos-dir-abs-path "/home/andy/clj/haironfire/repos")
#_(def repos-dir-abs-path "/home/andy/clj/haironfire/repos-small-test")
(def proj-locs (cloj/projects-retrieved repos-dir-abs-path))
(count proj-locs)

(def projerr (filter :error proj-locs))
(def projok (remove :error proj-locs))

(count projerr)
;; 1769
(count projok)
;; 13386

(def projerrgrp (group-by :description projerr))
(count projerrgrp)
(pprint (cloj/group-by->freqs projerrgrp))
;; {"Should be exactly 1 sub-directory" 1712,
;;  "File exit_status.txt should contain number 0 but found 1" 56,
;;  "Should be exactly 1 file named exit_status.txt" 1}

(pprint (projerrgrp "Should be exactly 1 file named exit_status.txt"))
;; Not sure why there is a repos/lark directory which has a .git
;; directory.  Maybe a problem in the git cloning script related to
;; the lark repos?  It seems to be the only directory like this.

(def projokgrp (group-by #(into (sorted-set) (keys (:tooling-files %))) projok))
(pprint (cloj/group-by->freqs projokgrp))
;; {
;;  ;; no deps.edn, but project.clj
;;  #{"project.clj"} 11509,
;;  #{"pom.xml" "project.clj"} 181,
;;  #{"build.boot" "project.clj"} 42,
;;  #{"build.boot" "pom.xml" "project.clj"} 4,
;;
;;  ;; neither deps.edn nor project.clj
;;  #{"pom.xml"} 137,
;;  #{"build.boot"} 558,
;;  #{} 418,
;;
;;  ;; deps.edn and project.clj
;;  #{"deps.edn" "project.clj"} 272,
;;  #{"deps.edn" "pom.xml" "project.clj"} 14,
;;  #{"build.boot" "deps.edn" "project.clj"} 2,
;;  #{"build.boot" "deps.edn" "pom.xml" "project.clj"} 1
;;
;;  ;; deps.edn but no project.clj
;;  #{"deps.edn"} 64,
;;  #{"deps.edn" "pom.xml"} 157,
;;  #{"build.boot" "deps.edn"} 23,
;;  #{"build.boot" "deps.edn" "pom.xml"} 4,
;; }

;; pg1 "Group 1" projects have no deps.edn file, but do have a project.clj file
;; pg2 "Group 2" projects have a deps.edn file, and a project.clj file

(def pg1 (filter #(and (contains? (:tooling-files %) "project.clj")
                       (not (contains? (:tooling-files %) "deps.edn")))
                 projok))
(count pg1)
;; 11,736 checks with the stats above

(def pg2 (filter #(and (contains? (:tooling-files %) "project.clj")
                       (contains? (:tooling-files %) "deps.edn"))
                 projok))
(count pg2)
;; 289 checks with the stats above

(def proj-group pg1)
(def proj-group pg2)

(def edn-read-fn clojure.edn/read)
(def d1 (cloj/categorize-lein-projects pg1 edn-read-fn))
(def d2 (cloj/categorize-lein-projects pg2 edn-read-fn))
(cloj/print-lein-project-summary d1)
(cloj/print-lein-project-summary d2)

;; There are definitely some differences in results between reading
;; using clojure.edn/read vs. clojure.tools.reader.edn/read.  These
;; differences could very well all be "works as designed slightly
;; differently" for clojure.tools.reader.edn/read, since we are
;; semi-abusing it by giving it files not meant to be legal EDN.  TBD:
;; Try to look for any unexpected or surprising differences in
;; behavior here, perhaps starting with just looking at differences in
;; the return value of the data read alone, without any follow-on
;; processing.

#_(def d1b (cloj/categorize-lein-projects pg1 clojure.tools.reader.edn/read))
#_(def d2b (cloj/categorize-lein-projects pg2 clojure.tools.reader.edn/read))
#_(= d1 d1b)
#_(= d2 d2b)
(cloj/print-lein-project-summary d1b)
(cloj/print-lein-project-summary d2b)


(count project-clj-fnames)
;; pg1 11736   pg2 289
(pprint (take 5 project-clj-fnames))

(def projinfos (mapv cloj/project-clj-file-info project-clj-fnames edn-read-fn))
(def excs (filter :exception projinfos))
(def ok (filter :first-form-defproject? projinfos))
(def bad (filter #(false? (:first-form-defproject? %)) projinfos))
(count projinfos)
;; 12078   pg1 11736
(count excs)
;; 975     pg1 917
(count ok)
;; 11064   pg1 10784
(count bad)
;; 39      pg1 35

(with-open [wrtr (io/writer "tmp.clj")]
  (binding [*out* wrtr]
    (doseq [pi ok]
      (pr (first (:forms pi)))
      (println ";;" (:filename pi)))))

(def gexcs (group-by cloj/my-exc-cause excs))
(pprint (cloj/group-by->freqs gexcs))
;; pg1:
;; {"No dispatch macro for: \"" 541,
;;  "Invalid leading character: ~" 263,
;;  "No dispatch macro for: (" 89,
;;  "Invalid leading character: `" 7,
;;  "No dispatch macro for: =" 6,
;;  "No dispatch macro for: '" 3,
;;  "Invalid token: ::min-lein-version" 3,
;;  "Invalid leading character: @" 2,
;;  "Map literal must contain an even number of forms" 1,
;;  "Invalid token: ::edge-features" 1,
;;  "Invalid token: ::url" 1}

;; pg2:

;; Examples of, and common reasons for, "No dispatch macro for: \"":
;; All of them are regexes, of course.  _Why_ the regexes are useful
;; in project.clj files is the reason for extra details here.

;; :jar-exclusions [#"^migrations/"]    ;; 335 files with :jar-exclusions found
;; :jar-inclusions
;; :uberjar-exclusions ; 23
;; :aot

;; documentation generation tool config for tools like:
;; :cljfmt - 26 occurrences
;; :codox - 19 occurrences
;; :autodoc - at least 1, but not on same line as regex so harder to grep for
;; e.g.
;; :autodoc {:load-except-list [#"internal"]}

;; other keywords whose values some people have used regex values,
;; some of them perhaps nested, i.e. not at the top level of the
;; defproject:

;; :auth
;; :exclude
;; :load-except-list
;; :namespaces
;; :ns-exclude-regex
;; :test-matcher

(pprint (gexcs "Map literal must contain an even number of forms"))
(pprint (gexcs "No dispatch macro for: ="))
(pprint (gexcs "Invalid leading character: @"))
(pprint (take 15 (gexcs "No dispatch macro for: \"")))


(def badbyreason (group-by :defproject-form-info bad))
(count badbyreason)
(pprint (keys badbyreason))
(pprint (cloj/group-by->freqs badbyreason))
;; pg1:
;;{{:error true, :description "List does not start with symbol 'defproject'"}
;; 30,
;; {:error true, :description "Not a list"} 3,
;; {:error true, :description "defproject list must have odd number of elements"}
;; 1,
;; {:error true, :description
;;  "defproject list had non-keywords where keyword expected, first beingfavicon"}
;; 1}


(def tmperr {:error true, :description "defproject list had non-keywords where keyword expected, first beingfavicon"})
(def tmperr {:error true, :description "Not a list"})
(pprint (take 5 (badbyreason tmperr)))


(pprint (nth ok 0))
(def okdeps (map (fn [pi]
                   (assoc pi :dependencies-check (cloj/good-lein-deps?
                                                  (:defproject-form-info pi))))
                 ok))
(pprint (nth okdeps 0))

(count okdeps)
(def ok-okdeps (filter #(false? (get-in % [:dependencies-check :error])) okdeps))
(def ok-baddeps (filter #(true? (get-in % [:dependencies-check :error])) okdeps))
(count ok-okdeps)
;; pg1 10768   pg2
(count ok-baddeps)
;; pg1 16

(def ok-baddeps-grps (group-by #(get-in % [:dependencies-check :description]) ok-baddeps))
(count ok-baddeps-grps)
(pprint (cloj/group-by->freqs ok-baddeps-grps))
(pprint ok-baddeps)




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
