[![Build Status](https://travis-ci.com/KTH/spork.svg?branch=master)](https://travis-ci.com/KTH/spork)
[![Code Coverage](https://codecov.io/gh/KTH/spork/branch/master/graph/badge.svg)](https://codecov.io/gh/KTH/spork)

# Spork - AST based merging for Java files
Spork is an AST based structured merge tool for Java. This means that instead of
merging Java files by their raw text, it resolves the abstract syntax trees and
merges based on them instead.

> **Early development:** Spork is in very early development. See the [issue
> tracker](https://github.com/kth/spork/issues) for known issues.

## Attribution
Spork is built on top of a few pieces of fantastic software, most notably:

* [Spoon](https;//github.com/inria/spoon) provides the AST representation used
  in Spork.
* [GumTree](https://github.com/gumtreediff/gumtree) provides the tree matching.
* [gumtree-spoon-ast-diff](https://github.com/spoonlabs/gumtree-spoon-ast-diff)
  bridges the gap between `Spoon` and `GumTree`.

The merge implementation in Spork is based on the [3DM merge algorithm by
Tancred Lindholm](https://doi.org/10.1145/1030397.1030399).

## Quickstart
Want to just try out Spork on a small merge scenario? Below are a few shell
commands that will download Spork along with a [sample merge
scenario](https://github.com/KTH/spork/tree/fe906f537d1bb7205256d1fe81fda9f323849a60/src/test/resources/clean/both_modified/move_if),
and then run it!

```bash
# Download Spork
wget https://github.com/KTH/spork/releases/download/v0.0.1/spork-0.0.1-SNAPSHOT-jar-with-dependencies.jar -O spork.jar

# Download a sample merge scenario
wget https://raw.githubusercontent.com/KTH/spork/fe906f537d1bb7205256d1fe81fda9f323849a60/src/test/resources/clean/both_modified/move_if/Left.java
wget https://raw.githubusercontent.com/KTH/spork/fe906f537d1bb7205256d1fe81fda9f323849a60/src/test/resources/clean/both_modified/move_if/Base.java
wget https://raw.githubusercontent.com/KTH/spork/fe906f537d1bb7205256d1fe81fda9f323849a60/src/test/resources/clean/both_modified/move_if/Right.java
# You should now have spork.jar, Left.java, Base.java and Right.java in your local directory

# a line based-merge is not possible
diff3 Left.java Base.java Right.java -m -A

# an AST-merge with Spork does
java -jar spork.jar Left.java Base.java Right.java
```

It should print the result of the merge to stdout. See `Base.java` for the
original version, and `Left.java` and `Right.java` for the respective changes.
They should all be neatly integrated into the resulting merge. For more on
using Spork, see [Usage](#usage).

## Usage
You can find a pre-built jar-file under
[relases](https://github.com/kth/spork/releases). The jar-file includes all
dependencies, so all you need is a Java runtime. Download the jar and run it
like so:

```
java -jar path/to/spork/jar <left> <base> <right> [expected]
```

The `left`, `base` and `right` arguments are required, and represent the left,
base and right revisions, respectively. The base revision should be the best
common ancestor of the left and right revisions. The `expected` argument is
optional. If provided, Spork will compare the merge it computed with `expected`
and say whether or not they match. This is mostly for development purposes.

> **Important:** Spork requires Java 8 or higher to run.

Naturally, if you want the absolute latest version, you will have to [build
Spork yourself](#build).

## Build
Maven can be used to build the latest version of Spork.

> **Note:** Requires JDK8+ to build.

```
mvn clean compile assembly:single
```

This will produce a jar-file in the `target` directory called something along the lines of
`spork-x.x.x-jar-with-dependencies.jar`. Run the jar with `java -jar path/to/spork/jar`.

## License
Unless otherwise stated, files in Spork are under the [MIT license](LICENSE).

* The
  [GumTreeSpoonAstDiff](src/main/java/se/kth/spork/merge/spoon/GumTreeSpoonAstDiff.java)
  file is composed of code from
  [gumtree-spoon-ast-diff](https://github.com/spoon/gumtree-spoon-ast-diff) and
  is therefore individually licensed under Apache License 2.0.
