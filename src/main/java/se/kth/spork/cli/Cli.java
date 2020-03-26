package se.kth.spork.cli;

import gumtree.spoon.AstComparator;
import gumtree.spoon.diff.Diff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import se.kth.spork.spoon.Compare;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import se.kth.spork.util.LineBasedMerge;
import se.kth.spork.util.Pair;
import spoon.reflect.declaration.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        LOGGER.info("Pre-processing tree for pretty-printing");
        new PrinterPreprocessor().scan(spoonRoot);

        CtPackage activePackage = spoonRoot.getRootPackage();
        while (!activePackage.getPackages().isEmpty()) {
            Set<CtPackage> subPkgs = activePackage.getPackages();
            Optional<CtPackage> pkgOpt = subPkgs.stream()
                    .filter(pkg -> !pkg.getTypes().isEmpty() || !pkg.getPackages().isEmpty())
                    .findFirst();

            if (!pkgOpt.isPresent())
                throw new RuntimeException("could not find the active package");

            activePackage = pkgOpt.get();
        }

        StringBuilder sb = new StringBuilder();
        if (!activePackage.isUnnamedPackage()) {
            sb.append("package ").append(activePackage.getQualifiedName()).append(";").append("\n\n");
        }

        Collection<?> imports = (Collection<?>) spoonRoot.getMetadata(Parser.IMPORT_STATEMENTS);
        for (Object imp : imports) {
            sb.append(imp).append("\n");
        }

        for (CtType<?> type : activePackage.getTypes()) {
            sb.append("\n\n").append(type);
        }

        return sb.toString();
    }

    @CommandLine.Command(mixinStandardHelpOptions = true, subcommands = {CompareCommand.class, MergeCommand.class},
            description = "The Spork command line app.", synopsisSubcommandLabel = "<COMMAND>")
    static class TopCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 1;
        }
    }

    @CommandLine.Command(name = "compare", mixinStandardHelpOptions = true,
            description = "Compare the ASTs of two Java files, disregarding comments and the order of unordered elements")
    private static class CompareCommand implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "LEFT", description = "Path to a Java file")
        File left;

        @CommandLine.Parameters(index = "1", paramLabel = "RIGHT", description = "Path to a Java file")
        File right;

        @Override
        public Integer call() {
            CtModule leftModule = Parser.parseWithoutComments(left.toPath());
            CtModule rightModule = Parser.parseWithoutComments(right.toPath());

            Compare.sortUnorderedElements(leftModule);
            Compare.sortUnorderedElements(rightModule);

            // reparse to avoid formatting inconsistencies
            leftModule = Parser.parse(prettyPrint(leftModule));
            rightModule = Parser.parse(prettyPrint(rightModule));

            Object leftImports = leftModule.getMetadata(Parser.IMPORT_STATEMENTS);
            Object rightImports = rightModule.getMetadata(Parser.IMPORT_STATEMENTS);

            Diff diff = new AstComparator().compare(leftModule, rightModule);
            System.out.println(diff);

            boolean importsEqual = leftImports.equals(rightImports);
            if (!importsEqual) {
                LOGGER.warn("Import statements differ");
                LOGGER.info("Left: " + leftImports);
                LOGGER.info("Right: " + rightImports);
            }

            return diff.getRootOperations().isEmpty() && importsEqual ? 0 : 1;
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

        @CommandLine.Option(
                names = {"-o", "--output"},
                description = "Path to the output file. Existing files are overwritten")
        File out;

        @CommandLine.Option(
                names = {"--git-mode"},
                description = "Enable Git compatibility mode. Required to use Spork as a Git merge driver."
        )
        boolean gitMode;

        @Override
        public Integer call() throws IOException {
            long start = System.nanoTime();

            Path basePath = base.toPath();
            Path leftPath = left.toPath();
            Path rightPath = right.toPath();

            if (gitMode) {
                basePath = gitCompatHardLink(basePath);
                leftPath = gitCompatHardLink(leftPath);
                rightPath = gitCompatHardLink(rightPath);

                basePath.toFile().deleteOnExit();
                leftPath.toFile().deleteOnExit();
                rightPath.toFile().deleteOnExit();
            }

            Pair<String, Boolean> merged = merge(basePath, leftPath, rightPath);
            String pretty = merged.first;
            boolean hasConflicts = merged.second;

            if (out != null) {
                LOGGER.info("Writing merge to " + out);
                Files.write(out.toPath(), pretty.getBytes(Charset.defaultCharset()),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                System.out.println(pretty);
            }

            LOGGER.info("Total time elapsed: " + (double) (System.nanoTime() - start) / 1e9 + " seconds");
            return hasConflicts ? 1 : 0;
        }

    }

    /**
     * Merge the three paths, that must point to Java files, using AST-based merge.
     *
     * @param base Path to base revision.
     * @param left Path to left revision.
     * @param right Path to right revision.
     * @return A pair on the form (prettyPrint, hasConflicts)
     */
    public static Pair<String, Boolean> merge(Path base, Path left, Path right) {
        LOGGER.info("Parsing input files");
        CtModule baseModule = Parser.parse(base);
        CtModule leftModule = Parser.parse(left);
        CtModule rightModule = Parser.parse(right);

        LOGGER.info("Initiating merge");
        Pair<CtElement, Boolean> merge = Spoon3dmMerge.merge(baseModule, leftModule, rightModule);
        CtModule mergeTree = (CtModule) merge.first;
        boolean hasConflicts = merge.second;

        LOGGER.info("Pretty-printing");
        if (containsTypes(mergeTree)) {
            return Pair.of(prettyPrint(mergeTree), hasConflicts);
        } else {
            LOGGER.warn("Merge contains no types (i.e. classes, interfaces, etc), reverting to line-based merge");
            String baseStr = Parser.read(base);
            String leftStr = Parser.read(left);
            String rightStr = Parser.read(right);
            return LineBasedMerge.merge(baseStr, leftStr, rightStr);
        }
    }

    /**
     * Create a hard link from a temporary git .merge_xxx file, with the name .merge_xxx.java. This is necessary for
     * Spork to be compatible with Git, as Spoon will only parse Java files if they actually have the .java file
     * extension.
     *
     * @param path Path to the temporary merge file.
     * @return A path to a new hard link to the file, but with a .java file extension.
     */
    private static Path gitCompatHardLink(Path path) throws IOException {
        if (!path.getFileName().toString().startsWith(".merge_file")) {
            throw new IllegalArgumentException(path + " not a Git merge file");
        }

        Path compatLink = path.resolveSibling(path.getFileName().toString() + ".java");
        try {
            Files.createLink(compatLink, path);
        } catch (UnsupportedOperationException x) {
            throw new IllegalStateException("Creating Git compatibility hard link not supported by file system");
        }

        return compatLink;
    }

    private static boolean containsTypes(CtElement elem) {
        List<CtType<?>> types = elem.getElements(e -> true);
        return types.size() > 0;
    }
}

