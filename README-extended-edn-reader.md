The results in this file use a hacked up version of Clojure 1.10.1's
EdnReader that allows reading of regex literals, without throwing an
exception.  The hacked up code changes can be seen
[here](https://github.com/jafingerhut/clojure/tree/hack-edn-read-to-read-regex-literals)
if you are curious.  The code was already there -- I just uncommented
it.

The fraction of `pg1` projects that become EDN readable with this
modified EdnReader, and otherwise also "looks good", goes up from
91.8% to 95.7%, a noticeable increase.

The next most common reason in these experiments that using the EDN
reader has for throwing exceptions is the use of `~`, typically before
sub-expressions to be evaluated, followed by `#( ...)` anonymous
functions.

```
user=> (cloj/print-lein-project-summary d1)

11736 projects analyzed
11236 (  95.7%) EDN-readable project.clj files with well formed :dependencies (or no :dependencies key)
  437 (   3.7%) threw exceptions while trying to read as EDN (breakdown 1 below)
   43 (   0.4%) EDN-readable, but something other than defproject form read first (breakdown 2 below)
   20 (   0.2%) defproject form first, but :dependencies look ill formed (breakdown 3 below)

Breakdown 1 of reasons for EDN reading of project.clj throwing exception:
{"Invalid leading character: ~" 286,
 "No dispatch macro for: (" 125,
 "Invalid leading character: `" 7,
 "No dispatch macro for: =" 6,
 "No dispatch macro for: '" 5,
 "Invalid token: ::min-lein-version" 3,
 "Invalid leading character: @" 2,
 "Map literal must contain an even number of forms" 1,
 "Invalid token: ::edge-features" 1,
 "Invalid token: ::url" 1}

Breakdown 2 of reasons first form doesn't appear to be a defproject:
{{:error true,
  :description "List does not start with symbol 'defproject'"}
 38,
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
 17,
 "One :dependencies element was a vector with 1 elements, not at least 2 as expected: [camel-snake-kebab]"
 1,
 "One :dependencies element was a vector with 1 elements, not at least 2 as expected: [puppetlabs/trapperkeeper-webserver-jetty9]"
 1,
 "One :dependencies element was a vector with 1 elements, not at least 2 as expected: [com.fasterxml.jackson.core/jackson-core]"
 1}
nil

user=> (cloj/print-lein-project-summary d2)

  289 projects analyzed
  265 (  91.7%) EDN-readable project.clj files with well formed :dependencies (or no :dependencies key)
   21 (   7.3%) threw exceptions while trying to read as EDN (breakdown 1 below)
    3 (   1.0%) EDN-readable, but something other than defproject form read first (breakdown 2 below)
    0 (   0.0%) defproject form first, but :dependencies look ill formed (breakdown 3 below)

Breakdown 1 of reasons for EDN reading of project.clj throwing exception:
{"Invalid leading character: ~" 14,
 "No dispatch macro for: =" 5,
 "Invalid leading character: @" 1,
 "No dispatch macro for: (" 1}

Breakdown 2 of reasons first form doesn't appear to be a defproject:
{{:error true,
  :description "List does not start with symbol 'defproject'"}
 3}

Breakdown 3 of reasons defproject :dependencies value looks wrong, or more likely needs examination of :managed-dependencies in this or a different project to interpret:
{}
nil
```
