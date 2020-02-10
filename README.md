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
