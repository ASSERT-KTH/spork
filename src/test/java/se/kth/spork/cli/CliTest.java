package se.kth.spork.cli;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import se.kth.spork.Util;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import spoon.reflect.declaration.CtModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliTest {

    @ParameterizedTest
    @ArgumentsSource(Util.LeftModifiedSourceProvider.class)
    void mergeTreeShouldEqualReParsedPrettyPrint_whenLeftIsModified(
            Util.TestSources sources, @TempDir Path tempDir) throws IOException {
        runTestMerge(sources, tempDir);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.RightModifiedSourceProvider.class)
    void mergeTreeShouldEqualReParsedPrettyPrint_whenRightIsModified(
            Util.TestSources sources, @TempDir Path tempDir) throws IOException {
        runTestMerge(sources, tempDir);
    }


    @ParameterizedTest
    @ArgumentsSource(Util.BothModifiedSourceProvider.class)
    void mergeTreeShouldEqualReParsedPrettyPrent_whenBothRevisionsAreModified(Util.TestSources sources, @TempDir Path tempDir) throws IOException {
        runTestMerge(sources, tempDir);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.ConflictSourceProvider.class)
    void prettyPrint_shouldContainConflict(Util.TestSources sources) throws IOException {
        List<Util.Conflict> expectedConflicts = Util.parseConflicts(sources.expected);

        CtModule merged = (CtModule) Spoon3dmMerge.merge(sources.base, sources.left, sources.right);

        String prettyPrint = Cli.prettyPrint(merged);

        List<Util.Conflict> actualConflicts = Util.parseConflicts(prettyPrint);

        System.out.println(prettyPrint);

        assertEquals(expectedConflicts, actualConflicts);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.ConflictSourceProvider.class)
    void prettyPrint_shouldParseToExpectedTree_whenConflictHasBeenStrippedOut(Util.TestSources sources) throws IOException {
        CtModule expected = Parser.parse(Util.keepLeftConflict(sources.expected));

        CtModule merged = (CtModule) Spoon3dmMerge.merge(sources.base, sources.left, sources.right);

        String prettyPrint = Cli.prettyPrint(merged);
        CtModule actual = Parser.parse(Util.keepLeftConflict(prettyPrint));

        assertEquals(expected, actual);
    }

    /**
     * Test the CLI by running merging the sources to a merge AST A, pretty printing A to a file and
     * parsing that file into a control tree B. If A and B are equal, no information has been lost in
     * the pretty print as far as Spoon knows (apart from import statements).
     *
     * @param sources
     */
    private static void runTestMerge(Util.TestSources sources, Path tempDir) throws IOException {
        CtModule merged = (CtModule) Spoon3dmMerge.merge(sources.base, sources.left, sources.right);

        Object expectedImports = merged.getMetadata(Parser.IMPORT_STATEMENTS);
        assert expectedImports != null;

        String expectedPrettyPrint = Cli.prettyPrint(merged);

        Path outFile = tempDir.resolve("Merge.java");
        Files.write(outFile, expectedPrettyPrint.getBytes(), StandardOpenOption.CREATE);

        CtModule reParsedMerge = Parser.parse(outFile);
        String reParsedPrettyPRint = Cli.prettyPrint(reParsedMerge);
        Object reParsedImports = reParsedMerge.getMetadata(Parser.IMPORT_STATEMENTS);

        // this assert is to give a better diff when there are obvious failures
        // it will most likely miss consistent errors in the pretty printer
        assertEquals(expectedPrettyPrint, reParsedPrettyPRint);

        // this assert is much more detailed, it should catch everything that exists inside the Spoon tree
        // that could potentially be lost in a pretty-print
        assertEquals(merged, reParsedMerge);

        assertEquals(reParsedImports, expectedImports);
    }

}