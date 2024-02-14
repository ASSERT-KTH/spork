package se.kth.spork.cli;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import kotlin.Pair;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import se.kth.spork.exception.MergeException;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import se.kth.spork.spoon.printer.PrinterPreprocessor;
import se.kth.spork.spoon.printer.SporkPrettyPrinter;
import se.kth.spork.util.LazyLogger;
import se.kth.spork.util.LineBasedMergeKt;
import spoon.Launcher;
import spoon.reflect.declaration.*;

/**
 * Command line interface for Spork.
 *
 * @author Simon Larsén
 */
public class Cli {
    private static final LazyLogger LOGGER = new LazyLogger(Spoon3dmMerge.class);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Merge()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Compose the output, assuming that spoonRoot is the merge of two files (i.e. the output is a
     * _single_ file).
     *
     * @param spoonRoot Root of a merged Spoon tree.
     * @return A pretty-printed string representing the merged output.
     */
    public static String prettyPrint(CtModule spoonRoot) {
        LOGGER.info(() -> "Pre-processing tree for pretty-printing");
        Optional<CtPackage> pkgOpt = findActivePackage(spoonRoot.getRootPackage());
        if (!pkgOpt.isPresent()) throw new RuntimeException("could not find the active package");

        CtPackage activePackage = pkgOpt.get();
        Collection<?> imports = (Collection<?>) spoonRoot.getMetadata(Parser.IMPORT_STATEMENTS);
        List<String> importNames =
                imports.stream()
                        .map(Object::toString)
                        .map(impStmt -> impStmt.substring("import ".length(), impStmt.length() - 1))
                        .collect(Collectors.toList());
        new PrinterPreprocessor(importNames, activePackage.getQualifiedName()).scan(spoonRoot);

        StringBuilder sb = new StringBuilder();

        String cuComment = (String) spoonRoot.getMetadata(Parser.COMPILATION_UNIT_COMMENT);
        if (!cuComment.isEmpty()) {
            sb.append(cuComment).append("\n");
        }

        if (!activePackage.isUnnamedPackage()) {
            sb.append("package ")
                    .append(activePackage.getQualifiedName())
                    .append(";")
                    .append("\n\n");
        }

        for (Object imp : imports) {
            sb.append(imp).append("\n");
        }

        for (CtType<?> type : activePackage.getTypes()) {
            sb.append("\n\n").append(type);
        }

        return sb.toString();
    }

    private static Optional<CtPackage> findActivePackage(CtPackage pkg) {
        if (!pkg.getTypes().isEmpty()) {
            return Optional.of(pkg);
        }

        return pkg.getPackages().stream()
                .map(Cli::findActivePackage)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    @CommandLine.Command(
            name = "spork",
            mixinStandardHelpOptions = true,
            description = "The Spork command line app.",
            versionProvider = SporkVersionProvider.class)
    static class Merge implements Callable<Integer> {
        @CommandLine.Parameters(
                index = "0",
                paramLabel = "LEFT",
                description = "Path to the left revision")
        File left;

        @CommandLine.Parameters(
                index = "1",
                paramLabel = "BASE",
                description = "Path to the base revision")
        File base;

        @CommandLine.Parameters(
                index = "2",
                paramLabel = "RIGHT",
                description = "Path to the right revision")
        File right;

        @CommandLine.Option(
                names = {"-o", "--output"},
                description = "Path to the output file. Existing files are overwritten.")
        File out;

        @CommandLine.Option(
                names = {"-e", "--exit-on-error"},
                description =
                        "Disable line-based fallback if the structured merge encounters an error.")
        boolean exitOnError;

        @CommandLine.Option(
                names = {"-g", "--git-mode"},
                description =
                        "Enable Git compatibility mode. Required to use Spork as a Git merge driver.")
        boolean gitMode;
        
        @CommandLine.Option(
                names = {"--diff3"},
                description =
                        "In conflicts, show the version at the base revision in addition to the left and right versions.")
        boolean diff3;

        @CommandLine.Option(
                names = {"-l", "--logging"},
                description = "Enable logging output")
        boolean logging;

        @Override
        public Integer call() throws IOException {
            if (logging) {
                setLogLevel("DEBUG");
            }
            Parser.INSTANCE.setDiff3(diff3);
            Spoon3dmMerge.INSTANCE.setDiff3(diff3);

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

            Pair<String, Integer> merged = merge(basePath, leftPath, rightPath, exitOnError);
            String pretty = merged.getFirst();
            int numConflicts = merged.getSecond();

            if (out != null) {
                LOGGER.info(() -> "Writing merge to " + out);
                Files.write(
                        out.toPath(),
                        pretty.getBytes(Charset.defaultCharset()),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                System.out.println(pretty);
            }

            LOGGER.info(
                    () ->
                            "Total time elapsed: "
                                    + (double) (System.nanoTime() - start) / 1e9
                                    + " seconds");
            return numConflicts % 127;
        }
    }

    /**
     * Merge the three paths, that must point to Java files, using AST-based merge.
     *
     * @param base Path to base revision.
     * @param left Path to left revision.
     * @param right Path to right revision.
     * @param exitOnError Disallow the use of line-based fallback if the structured merge encounters
     *     an error.
     * @return A pair on the form (prettyPrint, numConflicts)
     */
    public static Pair<String, Integer> merge(
            Path base, Path left, Path right, boolean exitOnError) {
        try {
            LOGGER.info(() -> "Parsing input files");
            CtModule baseModule = Parser.INSTANCE.parse(base);
            CtModule leftModule = Parser.INSTANCE.parse(left);
            CtModule rightModule = Parser.INSTANCE.parse(right);

            LOGGER.info(() -> "Initiating merge");
            Pair<CtElement, Integer> merge =
                    Spoon3dmMerge.INSTANCE.merge(baseModule, leftModule, rightModule);
            CtModule mergeTree = (CtModule) merge.getFirst();
            int numConflicts = merge.getSecond();

            LOGGER.info(() -> "Pretty-printing");
            if (containsTypes(mergeTree)) {
                return new Pair(prettyPrint(mergeTree), numConflicts);
            } else if (exitOnError) {
                throw new MergeException(
                        "Merge contained no types and global line-based fallback is disabled");
            } else {
                LOGGER.warn(
                        () ->
                                "Merge contains no types (i.e. classes, interfaces, etc), reverting to line-based merge");
                return lineBasedMerge(base, left, right);
            }
        } catch (Exception e) {
            if (exitOnError) {
                LOGGER.error(
                        () ->
                                "Spork encountered a fatal error and global line-based merge is disabled");
                throw e;
            } else {
                LOGGER.debug(e::getMessage);
                LOGGER.info(
                        () ->
                                "Spork encountered an error in structured merge. Falling back to line-based merge");
                return lineBasedMerge(base, left, right);
            }
        }
    }

    private static Pair<String, Integer> lineBasedMerge(Path base, Path left, Path right) {
        String baseStr = Parser.INSTANCE.read(base);
        String leftStr = Parser.INSTANCE.read(left);
        String rightStr = Parser.INSTANCE.read(right);
        return LineBasedMergeKt.lineBasedMerge(baseStr, leftStr, rightStr);
    }

    /**
     * Create a hard link from a temporary git .merge_xxx file, with the name .merge_xxx.java. This
     * is necessary for Spork to be compatible with Git, as Spoon will only parse Java files if they
     * actually have the .java file extension.
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
            throw new IllegalStateException(
                    "Creating Git compatibility hard link not supported by file system");
        }

        return compatLink;
    }

    private static boolean containsTypes(CtElement elem) {
        List<CtType<?>> types = elem.getElements(e -> true);
        return types.size() > 0;
    }

    private static void setLogLevel(String level) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        context.reset();
        context.putProperty("root-level", "level");
        try {
            jc.doConfigure(
                    Objects.requireNonNull(Cli.class.getClassLoader().getResource("logback.xml")));
        } catch (JoranException e) {
            LOGGER.error(() -> "Failed to set log level");
        }
    }
}
