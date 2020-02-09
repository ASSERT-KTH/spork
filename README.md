# spork
AST-based merge tool for Git based on Gumtree

## Build
Maven can be used to build the artifact. It currently just reports conflicts that are trivially found by 3DM
merge itself. Build with the following command:

> **Note:** Requires JDK10+ to build and JDK8+ to run.

```
mvn clean compile assembly:single
```

This will produce a jar-file in the `target` directory called `spork-1.0-SNAPSHOT-jar-with-dependencies.jar`.
Run the jar with `java -jar path/to/spork/jar`.
