package se.kth.spork.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.spoon.Compare;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import spoon.reflect.declaration.*;

import java.nio.file.Paths;
import java.util.Collection;

/**
 * Command line interface for Spork.
 *
 * @author Simon Larsén
 */
public class Cli {
    private static final Logger LOGGER = LoggerFactory.getLogger(Spoon3dmMerge.class);

    public static void main(String[] args) {
        long start = System.nanoTime();

        if (args.length < 3 || args.length > 4) {
            usage();
            System.exit(1);
        }


        LOGGER.info("Parsing input files");
        CtModule left = Parser.parse(Paths.get(args[0]));
        CtModule base = Parser.parse(Paths.get(args[1]));
        CtModule right = Parser.parse(Paths.get(args[2]));
        CtModule expected = args.length == 4 ? Parser.parse(Paths.get(args[3])) : null;

        LOGGER.info("Starting merge");
        CtModule merged = (CtModule) Spoon3dmMerge.merge(base, left, right);

        if (expected != null) {
            boolean preciselyEqual = expected.equals(merged);
            boolean equalDownToOrdering = Compare.compare(merged, expected);

            if (preciselyEqual && equalDownToOrdering) {
                System.out.println("Merged tree precisely matches expected tree");
            } else if (equalDownToOrdering) {
                System.out.println("Merged tree matches expected tree down to the order of unordered elements");
            } else {
                System.out.println("Merge tree does not match expected tree");
                System.exit(1);
            }
        } else {
            System.out.println(prettyPrint(merged));
        }

        LOGGER.info("Total time elapsed: " + (double) (System.nanoTime() - start) / 1e9 + " seconds");
    }

    /**
     * Compose the output, assuming that spoonRoot is the merge of two files (i.e. the output is a _single_ file).
     *
     * @param spoonRoot Root of a merged Spoon tree.
     * @return A pretty-printed string representing the merged output.
     */
    public static String prettyPrint(CtModule spoonRoot) {
        CtPackage activePackage = spoonRoot.getRootPackage();
        while (!activePackage.getPackages().isEmpty()) {
            assert activePackage.getPackages().size() == 1;
            activePackage = activePackage.getPackages().iterator().next();
        }

        StringBuilder sb = new StringBuilder();
        if (!activePackage.isUnnamedPackage()) {
            sb.append("package ").append(activePackage.getQualifiedName()).append(";").append("\n\n");
        }

        Collection<?> imports = (Collection<?>) spoonRoot.getMetadata(Parser.IMPORT_STATEMENTS);
        for (Object imp : imports) {
            sb.append(imp.toString()).append("\n");
        }

        for (CtType<?> type : activePackage.getTypes()) {
            sb.append(type.toString()).append("\n\n");
        }

        return sb.toString();
    }

    private static void usage() {
        System.out.println("usage: spork <left> <base> <right> [expected]");
    }
}
