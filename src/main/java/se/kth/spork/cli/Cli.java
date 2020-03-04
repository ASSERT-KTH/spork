package se.kth.spork.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import se.kth.spork.spoon.Compare;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.Callable;

/**
 * Command line interface for Spork.
 *
 * @author Simon Larsén
 */
public class Cli {
    private static final Logger LOGGER = LoggerFactory.getLogger(Spoon3dmMerge.class);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TopCmd()).execute(args);
        System.exit(exitCode);
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

    @CommandLine.Command(mixinStandardHelpOptions = true, subcommands = {CompareCommand.class, MergeCommand.class},
            description = "The Spork command line app.", synopsisSubcommandLabel = "<COMMAND>")
    private static class TopCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 1;
        }
    }

    @CommandLine.Command(name = "compare", mixinStandardHelpOptions = true,
            description = "Compare the ASTs of two Java files, disregarding the order of unordered elements")
    private static class CompareCommand implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "LEFT", description = "Path to a Java file")
        File left;

        @CommandLine.Parameters(index = "1", paramLabel = "RIGHT", description = "Path to a Java file")
        File right;

        @Override
        public Integer call() {
            CtModule leftModule = Parser.parse(left.toPath());
            CtModule rightModule = Parser.parse(right.toPath());

            if (Compare.compare(leftModule, rightModule)) {
                LOGGER.info("The ASTs are equal");
                return 0;
            }

            LOGGER.info("The ASTs differ");
            return 1;
        }
    }

    @CommandLine.Command(name = "merge", mixinStandardHelpOptions = true,
            description = "Perform a structured three-way merge")
    private static class MergeCommand implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "LEFT", description = "Path to the left revision")
        File left;

        @CommandLine.Parameters(index = "1", paramLabel = "BASE", description = "Path to the base revision")
        File base;

        @CommandLine.Parameters(index = "2", paramLabel = "RIGHT", description = "Path to the right revision")
        File right;

        @Override
        public Integer call() {
            long start = System.nanoTime();

            LOGGER.info("Parsing input files");
            CtModule baseModule = Parser.parse(base.toPath());
            CtModule leftModule = Parser.parse(left.toPath());
            CtModule rightModule = Parser.parse(right.toPath());

            LOGGER.info("Initiating merge");
            CtModule merge = (CtModule) Spoon3dmMerge.merge(baseModule, leftModule, rightModule);

            System.out.println(prettyPrint(merge));

            LOGGER.info("Total time elapsed: " + (double) (System.nanoTime() - start) / 1e9 + " seconds");
            return 0;
        }
    }
}

