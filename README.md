[![Build Status](https://travis-ci.com/KTH/spork.svg?branch=master)](https://travis-ci.com/KTH/spork)
[![Code Coverage](https://codecov.io/gh/KTH/spork/branch/master/graph/badge.svg)](https://codecov.io/gh/KTH/spork)

# Spork - AST based merging for Java files
Spork is an AST based structured merge tool for Java. This means that instead of
merging Java files by their raw text, it resolves the abstract syntax trees and
merges based on them instead.

## Master's thesis
Spork was created as part of a master's thesis project. If you want to learn
more about Spork in terms of theory and design, the thesis is freely available.

* [Spork: Move-enabled structured merge for Java with GumTree and 3DM](http://urn.kb.se/resolve?urn=urn:nbn:se:kth:diva-281960)

If you use Spork in for academic work, please reference the thesis.

```
@mastersthesis{larsen2020spork,
    title={Spork : Move-enabled structured merge for Java with GumTree and 3DM},
    url={http://urn.kb.se/resolve?urn=urn:nbn:se:kth:diva-281960},
    author={Larsén, Simon},
    year={2020},
    collection={TRITA-EECS-EX},
    series={TRITA-EECS-EX}
}
```

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
wget https://github.com/KTH/spork/releases/download/v0.5.0/spork-0.5.0.jar -O spork.jar

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

> **Important:** Spork requires a Java runtime version 8 or higher to run.

```
java -jar path/to/spork/jar <left> <base> <right>
```

The `left`, `base` and `right` arguments are required, and represent the left,
base and right revisions, respectively. The base revision should be the best
common ancestor of the left and right revisions.

For a full listing of the command line options, supply the `-h` option. It will
produce the following output.

```
Usage: spork [-eghlV] [-o=<out>] LEFT BASE RIGHT
The Spork command line app.
      LEFT              Path to the left revision.
      BASE              Path to the base revision.
      RIGHT             Path to the right revision.
  -e, --exit-on-error   Disable line-based fallback if the structured merge
                          encounters an error.
  -g, --git-mode        Enable Git compatibility mode. Required to use Spork as
                          a Git merge driver.
  -h, --help            Show this help message and exit.
  -l, --logging         Enable logging output.
  -o, --output=<out>    Path to the output file. Existing files are overwritten.
  -V, --version         Print version information and exit.
```

Naturally, if you want the absolute latest version, you will have to [build
Spork yourself](#build).

## Build
Maven can be used to build the latest version of Spork.

> **Note:** Requires JDK8+ to build.

```
mvn clean compile package -DskipTests
```

This will produce a jar-file in the `target` directory called something along
the lines of `spork-x.x.x.jar`. Run the jar with `java -jar path/to/spork/jar`.

## Configure as a Git merge driver
When Git performs a merge and encounters a file that has been edited in both revisions under merge, it will invoke a
merge driver to merge the conflicting versions. It's a very simple thing to configure Spork as a merge driver for Java
files, all you need is to add a couple of lines to a couple of configuration files. First, let's create a
`.gitattributes` file and specify to use Spork as a merge driver for Java files. Put the following content in your
`.gitattributes` file (you may all ready have one, check your home directory):

```
*.java merge=spork
```

`spork` doesn't mean anything to Git yet, we need to actually define the merge driver called `spork`. We do that in the
`.gitattributes` file, typically located in your home directory. You should put the following content into it:

```
[core]
	attributesfile = /path/to/.gitattributes

[merge "spork"]
    name = spork
    driver = java -jar /path/to/spork.jar merge --git-mode %A %O %B -o %A
```

Then replace `/path/to/.gitattributes` with the absolute path to the `.gitattributes` file you edited/created first,
and replace `/path/to/spork.jar` with the absolute path to the Spork jar-file. With that done, Spork will be used
as the merge driver for Java files!

> **Important:** The `--git-mode` option is required to use Spork as a Git merge driver. If you find that Spork always
> reverts to line-based merge, then that option is probably missing in the `driver` option that invokes Spork.

## License
Unless otherwise stated, files in Spork are under the [MIT license](LICENSE).

* The
  [GumTreeSpoonAstDiff](src/main/java/se/kth/spork/spoon/GumTreeSpoonAstDiff.java)
  file is composed of code from
  [gumtree-spoon-ast-diff](https://github.com/spoon/gumtree-spoon-ast-diff) and
  is therefore individually licensed under Apache License 2.0.
