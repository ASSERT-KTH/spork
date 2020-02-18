package se.kth.spork.cli;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.merge.spoon.Spoon3dmMerge;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Command line interface for Spork.
 *
 * @author Simon Larsén
 */
public class Cli {
    private static final Logger LOGGER = LoggerFactory.getLogger(Spoon3dmMerge.class);

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            usage();
            System.exit(1);
        }

        LOGGER.info("Reading input files");
        String left = readFile(args[0]);
        String base = readFile(args[1]);
        String right = readFile(args[2]);
        String expected = args.length == 4 ? readFile(args[3]) : null;

        LOGGER.info("Parsing input files to Spoon trees");
        CtClass<?> baseTree = Launcher.parseClass(base);
        CtClass<?> leftTree = Launcher.parseClass(left);
        CtClass<?> rightTree = Launcher.parseClass(right);

        LOGGER.info("Starting merge");
        CtElement mergedTree = Spoon3dmMerge.merge(baseTree, leftTree, rightTree);

        if (expected != null) {
            CtElement expectedTree = Launcher.parseClass(expected);
            boolean isEqual = expectedTree.toString().equals(mergedTree.toString());

            if (!isEqual) {
                System.out.println("EXPECTED");
                System.out.println(expected);
                System.out.println();

                System.out.println("ACTUAL");
                System.out.println(mergedTree);
            } else {
                System.out.println("Merged file matches expected file");
            }
        } else {
            System.out.println(mergedTree);
        }
    }

    private static String readFile(String s) throws IOException {
        Path path = Paths.get(s);
        if (!path.toFile().isFile())
            throw new IllegalArgumentException("no such file: " + path);
        return Files.lines(path).collect(Collectors.joining("\n"));
    }

    private static ITree toGumTree(String clazz) {
        CtClass<?> spoonTree = Launcher.parseClass(clazz);
        SpoonGumTreeBuilder builder = new SpoonGumTreeBuilder();
        return builder.getTree(spoonTree);
    }

    private static Matcher matchTrees(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher(src, dst);
        matcher.match();
        return matcher;
    }

    private static void usage() {
        System.out.println("usage: spork <left> <base> <right> [expected]");
    }
}
