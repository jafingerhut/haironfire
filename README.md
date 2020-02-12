# Introduction

The name is a reference to the Leiningen software's motto: "for
automating Clojure projects without setting your hair on fire".  If
you have not read the short story ["Leiningen Versus the
Ants"](http://www.classicshorts.com/stories/lvta.html), from which the
Leiningen software gets its name, I highly recommend it.

The code in this project was written in an attempt to answer the
following question: What fraction of Clojure projects that use
Leiningen, have a `project.clj` file that is in EDN format, with no
executable code?

Background: Many Leiningen `project.clj` files contain a single
Clojure list starting with `defproject`, and contain only Clojure
data, with no executable code.  Such a file can be read using
Clojure's `clojure.edn/read` function without error, and without any
loss of the information intended by the author of that `project.clj`
file.  Such files also create no security concerns of executing
untrusted code, in order for a consumer to extract that data.

However, `project.clj` file contents are `eval`d by Leiningen, which
enables developers to write executable code in them.  In such files,
the value of any or all portions of the `defproject` form cannot be
known without evaluating the `project.clj` file.

TBD: Find an example or two of `project.clj` files of actual Clojure
projects that do this, if I can, and link to them.

Alex Miller asked in an on-line discussion about the tools.deps
library in February 2020 what the actual percentage of Leiningen files
was that contained no executable code.  His motivation for asking was
because if this percentage is very high, e.g. 99%, it could become
worth the time to enable tools.deps to use `clojure.edn/read` to
extract the value of the `:dependencies` key in the `defproject` form
to get the dependencies of a Leiningen project in source code,
e.g. via a git commit SHA reference to the Leiningen project source
code in a `deps.edn` file.

If the percentage is much lower, e.g. 80%, it might not be worth doing
this in tools.deps, because it would fail for so many projects.  While
the suggestion was made that one could in tools.deps try to `eval` the
contents of a `project.clj` file in a sandboxed environment,
e.g. using something similar to the
[`clojail`](https://github.com/Raynes/clojail) library, Alex consders
such an approach to be too much effort to develop and maintain over
time to be worth it.

Related questions:

A `defproject` form can also have `:dependencies` inside of one or
more Leiningen profiles, defined within a `:profile` key of the
`defproject` form.  It is possible for the deployed version of a
library to use one of those profiles in order to determine the actual
dependencies of the published project artifact (e.g. a JAR file) on
Clojars or Maven.  It would be nice to compare the top level
`:dependencies` value against the actual dependencies published in a
Clojars or Maven JAR file, to see if they are the same as the top
level `:dependencies` value of the `project.clj` file.

How many of the published JAR files contain one or more of these files
in the 'root directory' of the JAR?

+ `project.clj`
+ `pom.xml`
+ `deps.edn`
+ `boot.build`

How many of the published JAR files contain one or more of those files
in the root directory of the Git repository of the source code?

How many have one or more files with those names that are in a
directory that is not the root directory of the JAR file?  Of the
source code?

Do the git projects use git submodules?

Is the deployed JAR created from the `master` branch of the source, or
a different one?

While we are collecting data, it might be interesting to know what
percentage of `project.clj` file use each of the different keys like
`:dependencies`, `:profiles`, `:aot`, etc.


# Data sources

This Clojars documentation page gives several programmatic ways to
retrieve data about projects hosted on Clojars:

+ https://github.com/clojars/clojars-web/wiki/Data

After reading the choices available, and looking at the data returned
by a few of the URLs, the one below seems to be the most useful for my
purposes, because this file contains URLs for the source code of each
project:

+ http://clojars.org/repo/feed.clj.gz


# Data collected, and analysis results

The goal here is not to guarantee any kind of completeness of Clojure
projects to analyze, but to have a large sample.

The `feed.clj.gz` file I started with I downloaded on 2020-Feb-08.  It
seemed to work better using a web browser to go to the URL and get the
file -- for reasons I did not discover, `curl` failed to get the file
using that URL.

I decompressed the file to get `feed.clj` and did the following in my
REPL, using the Clojure code in this repository.

First, the steps to read and process the contents of the `feed.clj`
file, and create a bash script to download many git repositories of
source code for as many of the projects that we can find a Github URL
for.

```clojure
(import '(java.io PushbackReader))
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[com.fingerhutpress.haironfire.clojars :as cloj])

(def edn-read-fn clojure.edn/read)
(def tmp (let [r1 (PushbackReader. (io/reader "feed.clj"))]
           (-> r1
               (cloj/read-clojars-feed edn-read-fn)
               cloj/summarize-clojars-feed-data)))
;; `as` is short for 'artifacts'
(def as (cloj/add-canonical-artifact-ids (:data tmp)))
(print (:string tmp))

;; 25675 artifacts, where
;; 25675 remain after removing ones with 'unclean' data, out of which:
;; 
;; 18493 (  72.0%) appear to have usable URLs for accessing source code,
;;   971 (   3.8%) have URLs that are less likely to be usable, and
;;  6211 (  24.2%) no URL likely to be usable at all was found.
;; 
;; 25675 ( 100.0%) have unique (group-id:artifact-id) pair
;;     0 (   0.0%) have (group-id:artifact-id) pair that is duplicate of another
;; 
;; Artifacts partitioned into sets based on what kind of SCM :connection
;; or :url key was found, or a :url key at the top level of the map:
;;  2280 (   8.9%) no-scm-and-no-url
;;   673 (   2.6%) scm-connection-git-but-missing-project-name
;;     1 (   0.0%) scm-connection-git-not-github
;;   398 (   1.6%) scm-connection-git-then-non-git
;; 14769 (  57.5%) scm-connection-github
;;    99 (   0.4%) scm-connection-http-github-url
;;    66 (   0.3%) scm-connection-not-git
;;    12 (   0.0%) scm-connection-not-scm
;;   915 (   3.6%) scm-no-connection-and-empty-url
;;  2530 (   9.9%) scm-no-connection-and-github-url
;;  1543 (   6.0%) scm-no-connection-and-no-url
;;   101 (   0.4%) scm-no-connection-and-non-github-url
;;    64 (   0.2%) scm-no-connection-and-yes-url
;;   195 (   0.8%) url-empty-string
;;   447 (   1.7%) url-http-example-fixme
;;  1094 (   4.3%) url-http-or-https-github
;;   472 (   1.8%) url-http-or-https-not-github
;;    16 (   0.1%) url-not-httpnil

(count as)

;; The next step writes the file get-artifact-source.sh and is pretty
;; quick.  If you do this yourself, pick a directory name that does
;; not exist yet on your system, or is empty.  The bash script will
;; create it if it does not exist already.

(def repos-dir-abs-path "/home/andy/clj/haironfire/repos")
(cloj/write-git-clone-bash-script "get-artifact-source.sh" repos-dir-abs-path
                                  as)
```

When I went through the steps above, here is how the stats came out:

+ 25,675 artifacts described in the `feed.clj` file
+ 18,493 of those were categorized as having 'usable URLs'.
  + The details of that can be found in the code, but basically it
    means that the code found a URL that looks like a valid Github
    project URL.
+ 15,154 of those URLs were distinct.
  + This number is not in the output above -- I ran the command
    `grep -c 'git clone ' get-artifact-source.sh`
    on the bash script to find that number.
  + It seems that many Clojars artifacts have the same Github URL as
    each other.  I believe this is mostly because some developers use
    a single source code repository to generate multiple related JAR
    files.

_Executing_ the bash script `get-artifact-source.sh` can take many
hours, depending upon your Internet access speed, and the number of
projects it attempts to get.

When I ran it in 2020-Feb, it took most of one day to run, attempting
to do 'git clone' on 15,154 URLs.

It created files totaling about about 42 Gbytes of space.  Most of the
git repositories are pretty small.  The largest 90 of them took half
of that storage, just over 21 Gbytes.  The remaining (13,386-90) that
were successfully cloned (see below) took an average of about 1.6
Mbytes of space each.

Some of those URLs were invalid, probably because they were entered
incorrectly when the Clojars artifact was created, or they were valid
when the Clojars artifact was created, but had since been removed.

Once that long step is done, this next bit below tells us how many git
clones were done successfully.

```clojure

;; Same directory as above, so no reason to def this again if you are
;; in the same REPL session.

(def repos-dir-abs-path "/home/andy/clj/haironfire/repos")
(def proj-locs (cloj/projects-retrieved repos-dir-abs-path))
(def projok (remove :error proj-locs))
(count projok)
;; 13386
```

So of the 15,154 distinct URLs, I was able to successfully download
13,386 repositories.  I have not checked carefully, but I suspect most
of the failures were due to wrong URLs entered in the Clojars
artifact, or they were correct at one time, but have since been
removed.

Continuing the analysis on those 13,386 repositories, let us divide
them up into groups, based upon which of the 4 tooling files they have
in their home directory.

```clojure
(def projok-grouped (group-by #(into (sorted-set) (keys (:tooling-files %))) projok))

;; I have edited the output of the pprint below, to group things
;; together as I wish and add a few comments.

(pprint (cloj/group-by->freqs projok-grouped))

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
```

"Group 1" projects in `pg1` have no `deps.edn` file, but do have a
`project.clj` file.

"Group 2" projects in `pg2` have a `deps.edn` file and a `project.clj`
file.

```clojure
(def pg1 (filter #(and (contains? (:tooling-files %) "project.clj")
                       (not (contains? (:tooling-files %) "deps.edn")))
                 projok))
(count pg1)
;; 11736

(def pg2 (filter #(and (contains? (:tooling-files %) "project.clj")
                       (contains? (:tooling-files %) "deps.edn"))
                 projok))
(count pg2)
;; 289

(def d1 (cloj/categorize-lein-projects pg1 edn-read-fn))
(def d2 (cloj/categorize-lein-projects pg2 edn-read-fn))
```

For the output below, I am prefixing the expressions I evaluated with
`user=>`, and _not_ commenting the output, since it is fairly long and
I do not want to bother commenting it all.

Note that the most common reason for the EDN reader throwing an
exception while reading `project.clj` files is the use of regex
literals.  See [here](README-extended-edn-reader.md) for alternate
results for a hacked up version of Clojure's `EdnReader` that can read
regex literals.

```
user=> (cloj/print-lein-project-summary d1)

11736 projects analyzed
10768 (  91.8%) EDN-readable project.clj files with well formed :dependencies (or no :dependencies key)
  917 (   7.8%) threw exceptions while trying to read as EDN (breakdown 1 below)
   35 (   0.3%) EDN-readable, but something other than defproject form read first (breakdown 2 below)
   16 (   0.1%) defproject form first, but :dependencies look ill formed (breakdown 3 below)

Breakdown 1 of reasons for EDN reading of project.clj throwing exception:
{"No dispatch macro for: \"" 541,
 "Invalid leading character: ~" 263,
 "No dispatch macro for: (" 89,
 "Invalid leading character: `" 7,
 "No dispatch macro for: =" 6,
 "No dispatch macro for: '" 3,
 "Invalid token: ::min-lein-version" 3,
 "Invalid leading character: @" 2,
 "Map literal must contain an even number of forms" 1,
 "Invalid token: ::edge-features" 1,
 "Invalid token: ::url" 1}

Breakdown 2 of reasons first form doesn't appear to be a defproject:
{{:error true,
  :description "List does not start with symbol 'defproject'"}
 30,
 {:error true, :description "Not a list"} 3,
 {:error true,
  :description "defproject list must have odd number of elements"}
 1,
 {:error true,
  :description
  "defproject list had non-keywords where keyword expected, first beingfavicon"}
 1}

Breakdown 3 of reasons defproject :dependencies value looks wrong, or more likely needs examination of :managed-dependencies in this or a different project to interpret:
{"One :dependencies element was a vector with 1 elements, not at least 2 as expected: [org.clojure/clojure]"
 14,
 "One :dependencies element was a vector with 1 elements, not at least 2 as expected: [puppetlabs/trapperkeeper-webserver-jetty9]"
 1,
 "One :dependencies element was a vector with 1 elements, not at least 2 as expected: [com.fasterxml.jackson.core/jackson-core]"
 1}
nil


user=> (cloj/print-lein-project-summary d2)

  289 projects analyzed
  237 (  82.0%) EDN-readable project.clj files with well formed :dependencies (or no :dependencies key)
   51 (  17.6%) threw exceptions while trying to read as EDN (breakdown 1 below)
    1 (   0.3%) EDN-readable, but something other than defproject form read first (breakdown 2 below)
    0 (   0.0%) defproject form first, but :dependencies look ill formed (breakdown 3 below)

Breakdown 1 of reasons for EDN reading of project.clj throwing exception:
{"No dispatch macro for: \"" 31,
 "Invalid leading character: ~" 13,
 "No dispatch macro for: =" 5,
 "Invalid leading character: @" 1,
 "No dispatch macro for: (" 1}

Breakdown 2 of reasons first form doesn't appear to be a defproject:
{{:error true,
  :description "List does not start with symbol 'defproject'"}
 1}

Breakdown 3 of reasons defproject :dependencies value looks wrong, or more likely needs examination of :managed-dependencies in this or a different project to interpret:
{}
nil
```


# Things I learned while examining the data

## Maven SCM strings

Strings beginning with `scm:` such as
`scm:git:ssh://git@github.com/quoll/naga.git` are used as values of
the `connection` and/or `developerConnection` property in a Maven
`pom.xml` file to specify the location of the source code of a
project.  They are documented at least at the link below, and probably
other places:

+ http://maven.apache.org/pom.html#SCM

The string that appears after the initial `scm:` and before the second
`:` character are called a "provider" in the Maven documentation.

The providers I have seen used on Clojars are:

+ `bazaar` - no others begin with `b`
+ `cvs` - no others begin with `c`
+ `git` - no others begin with `g`
+ `hg` - one begins with `scm:http`, but nothing else begins with `scm:h`
+ `svn` - no others begin with `s`
+ No others that start with any letter other than `[bcghs]`.


## EDN is a subset of Clojure code in many ways

I already knew this before, but a bit more surprising is one way I had
never seen or thought of before (see below), and the reminder that
Clojure regex literals are not part of EDN.  Regex literals are used
in a noticeable fraction of `project.clj` files, most often as file
name patterns to include/exclude for some purpose, e.g. selecting
files to put into, or not put into, a JAR, or to include or exclude
for generating documentation.

When reading text using `clojure.edn/read`, the single quote character
`'` is treated as a character that is part of a symbol.  Thus if you
read Clojure data using `clojure.edn/read` that was written intended
to be read by the code reading function `clojure.core/read`, as
Leiningen `project.clj` files are, single quotes that read fine like
this:

```clojure
$ clj
Clojure 1.10.1

;; Most people would not quote a number, but you can do that in
;; Clojure code, and it will read as an experienced Clojure developer
;; would expect, expanding '1 into (quote 1), which is a list (type
;; Cons in this case) of the symbol `quote` and the number `1`.

user=> (def m1 (clojure.core/read-string "{:a '1}"))
#'user/m1

user=> m1
{:a (quote 1)}

user=> (m1 :a)
(quote 1)

user=> (class (m1 :a))
clojure.lang.Cons

user=> (map class (m1 :a))
(clojure.lang.Symbol java.lang.Long)


;; Something different happens when you read it using EDN reader.

user=> (def m2 (clojure.edn/read-string "{:a '1}"))
#'user/m2

;; This output certainly looks different than above.

user=> m2
{:a '1}

user=> (m2 :a)
'1

;; What is the type of the value in the map?

user=> (class (m2 :a))
clojure.lang.Symbol

;; A symbol!  That is an odd looking symbol, indeed.  And its name
;; string include the single quote character!

user=> (name (m2 :a))
"'1"


;; Using a quote with the clojure.core read or read-string function is
;; more typical than quoting a number.

user=> (clojure.core/read-string "{:a '{:b 2}}")
{:a (quote {:b 2})}

;; That looks expected.  What about when we read the same string using
;; the EDN reader?  It treats the ' by itself as symbol with a name
;; one character long, and separate from the map `{:b 2}` that follows
;; it, so that the thing that looks like a map around it, has 3 values
;; between the outer braces, not 2, and is not a legal map.

user=> (clojure.edn/read-string "{:a '{:b 2}}")
Execution error at user/eval17 (REPL:1).
Map literal must contain an even number of forms
```

The good news is that there is code in this project to check, after
reading a `project.clj` file using `clojure.edn/read`, whether any of
the dependencies symbols contain a single quote character anywhere in
the name of the symbol, and 0 of them did.


## A `defproject` form can have no `:dependencies` key

As far as I can tell, Leiningen treats this the same as having an
empty sequence of dependencies.  Almost 1,000 `project.clj` files out
of 12,000 EDN-readable ones I checked are like this, so it is
reasonably common.


## A `project.clj` `:dependencies` value can be just about anything whose `seq` is a sequence of vectors or lists

Most `project.clj` files have a `:dependencies` key whose value is a
vector of vectors, like this example:

```clojure
(defproject useclj110 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 ["medley" "1.2.0"]])
```

But it can also be a list of vectors, a list of lists, a vector of
lists, or even a Clojure map, which when you call `seq` on it returns
a sequence of 2-element vectors.  The examples below are equivalent to
the one above, as far as Leiningen is concerned:

```clojure
;; :dependencies is a list of vectors

(defproject useclj110 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies ([org.clojure/clojure "1.10.1"]
                 ["medley" "1.2.0"]))

;; dependencies is a map

(defproject useclj110 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies {org.clojure/clojure "1.10.1"
                 "medley" "1.2.0"})
```


## Elements of a `:dependencies` value can contain only a symbol, with no version

If you try to give a dependency with a group/artifact id, but no
version, it is normally an error for Leiningen:

```clojure
;; This will cause Leiningen to give an error

(defproject useclj110 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure]
                 [medley]])
```

The `project.clj` file below is legal for Leiningen, and
`org.clojure/data.priority-map` does _not_ appear in the output of
`lein deps :tree`, by design.  The output of `lein deps :tree` shows
the versions of `org.clojure/clojure` and `medley` that appear in the
value of the `:managed-dependencies` key.

```clojure
(defproject useclj110 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure]
                 [medley]]
  :managed-dependencies [[org.clojure/clojure "1.9.0"]
                         [medley "1.2.0"]
                         [org.clojure/data.priority-map "0.0.10"]])
```

I do not now what tools.deps should do with such dependencies,
especially if they are between multiple projects.  Within a single
`project.clj` file like above, it may be more straightforward to
handle.

I only saw 16 out of about 12,000 EDN-readable `project.clj` files
that had these kinds of dependencies, so perhaps it is best to treat
these as unusable for the purposes of tools.deps.
