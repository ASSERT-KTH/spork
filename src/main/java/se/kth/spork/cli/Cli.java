package se.kth.spork.cli;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.merge.spoon.Spoon3dmMerge;
import spoon.Launcher;
import spoon.reflect.declaration.*;

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

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            usage();
            System.exit(1);
        }


        LOGGER.info("Reading input files");
        CtModule left = Spoon3dmMerge.parse(Paths.get(args[0]));
        CtModule base = Spoon3dmMerge.parse(Paths.get(args[1]));
        CtModule right = Spoon3dmMerge.parse(Paths.get(args[2]));
        CtModule expected = args.length == 4 ? Spoon3dmMerge.parse(Paths.get(args[3])) : null;

        LOGGER.info("Parsing input files to Spoon trees");

        LOGGER.info("Starting merge");
        CtModule merged = (CtModule) Spoon3dmMerge.merge(base, left, right);

        if (expected != null) {
            boolean isEqual = expected.equals(merged);

            if (!isEqual) {
                System.out.println("EXPECTED");
                System.out.println(composeOutput(expected));
                System.out.println();

                System.out.println("ACTUAL");
                System.out.println(composeOutput(merged));
            } else {
                System.out.println("Merged file matches expected file");
            }
        } else {
            System.out.println(composeOutput(merged));
        }
    }

    /**
     * Compose the output, assuming that spoonRoot is the merge of two files (i.e. the output is a _single_ file).
     *
     * @param spoonRoot Root of a merged Spoon tree.
     * @return A pretty-printed string representing the merged output.
     */
    private static String composeOutput(CtModule spoonRoot) {
        CtPackage activePackage = spoonRoot.getRootPackage();
        while (!activePackage.getPackages().isEmpty()) {
            assert activePackage.getPackages().size() == 1;
            activePackage = activePackage.getPackages().iterator().next();
        }

        StringBuilder sb = new StringBuilder();
        for (CtType<?> type : activePackage.getTypes()) {
            sb.append(type.toString()).append("\n\n");
        }

        return sb.toString();
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
