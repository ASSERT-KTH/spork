# Spork - AST based merging for Java files
Spork is an AST based structured merge tool for Java. That means that instead of
merging Java files by their raw text, it resolves tha abstract syntax trees and
merges based on these instead.

> **Early development:** Spork is in very early development. See the [issue
> tracker](https://github.com/kth/spork/issues) for known issues.

Spork is built on top of a set of fantastic software, most notably:

* [Spoon](https;//github.com/inria/spoon) provides the AST representation used
  in Spork.
* [GumTree](https://github.com/gumtreediff/gumtree) provides the tree matching.
* [gumtree-spoon-ast-diff](https://github.com/spoonlabs/gumtree-spoon-ast-diff)
  bridges the gap between `Spoon` and `GumTree`.

The merge implementation in Spork is based on the [3DM merge algorithm by
Tancred Lindholm](https://doi.org/10.1145/1030397.1030399).

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

> **Note:** Requires JDK10+ to build and a Java 8+ runtime to run.

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
