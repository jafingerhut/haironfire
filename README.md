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

+ `project.clj` file
+ `pom.xml` file
+ `deps.edn` file

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
by a few of the URLs, I think the one below might be the most useful
for my purposes, because this file contains URLs for the source code
of each project:

+ http://clojars.org/repo/feed.clj.gz


# Data to collect

Clojars has data on a large fraction of open source Clojure projects.
While it would be possible to use Github.com APIs to retrieve data on
Clojure projects that deploy artifacts elsewhere, e.g. Maven central,
or do not deploy artifacts at all, I will not try to do that yet, and
perhaps never, in hopes that the Clojars data will be a representative
sample.

```bash
$ curl -O http://clojars.org/repo/feed.clj.gz
```

Strangely enough, using that `curl` command sometimes gives me back a
short 301 or 302 response rather than the data, whereas that URL seems
to work fine from a browser like Firefox.  I do not know why.


# Maven SCM strings

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



# Things I learned while examining the data

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
