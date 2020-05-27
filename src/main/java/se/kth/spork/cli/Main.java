package se.kth.spork.cli;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import se.kth.spork.spoon.printer.SporkPrettyPrinter;
import se.kth.spork.util.LazyLogger;
import se.kth.spork.util.Pair;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Main {
    //static final Path dirpath = Paths.get("src/test/resources/clean/left_modified/generic_with_lower_bound");
    //static final Path dirpath = Paths.get("src/test/resources/clean/left_modified/edit_annotations");
    //static final Path dirpath = Paths.get("src/test/resources/conflict/arraylist");
    //static final Path dirpath = Paths.get(".");
    //static final Path dirpath = Paths.get("src/test/resources/clean/left_modified/delete_method");
    //static final Path dirpath = Paths.get("/tmp/tmpdir10732900622637231053/");
    //static final Path dirpath = Paths.get("src/test/resources/clean/left_modified/IGNORE_rxjava");
    //static final Path dirpath = Paths.get("/home/slarse/Documents/github/cdate/spork-benchmark/scripts/merge_directory/51db3d9bce341fe9c07c6c499c26491a8603493b/KeyEventSource.java");

    static final Path dirpath = Paths.get("/home/slarse/Documents/github/cdate/merge-testing/run/merge_dirs/wordnik_swagger-codegen/0bb610494e6e91a7a67b3fa82049d55c85e05bc4/FlaskConnexionCodegen.java_1ae2d60c974aeac1183c07cf4a9dd1dbad421b3d");


    public static void main(String[] args) {
        Path left = dirpath.resolve("Left.java");
        Path right = dirpath.resolve("Right.java");
        Path base = dirpath.resolve("Base.java");

        Pair<CtModule, Integer> mergePair = Spoon3dmMerge.merge(base, left, right);

        System.out.println(Cli.prettyPrint(mergePair.first));
    }

    public static int slowValue() {
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return 1;
    }


    public static String read(String path) throws IOException {
        return String.join("\n", Files.readAllLines(Paths.get(path)));
    }
}
