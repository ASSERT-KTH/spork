package se.kth.spork.spoon;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import se.kth.spork.Util;
import se.kth.spork.cli.Cli;
import se.kth.spork.exception.ConflictException;
import se.kth.spork.util.Pair;
import spoon.reflect.declaration.*;

class Spoon3dmMergeTest {

    @ParameterizedTest
    @ArgumentsSource(Util.LeftModifiedSourceProvider.class)
    void mergeToTree_shouldReturnExpectedTree_whenLeftVersionIsModified(Util.TestSources sources) {
        runTestMerge(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.RightModifiedSourceProvider.class)
    void mergeToTree_shouldReturnExpectedTree_whenRightVersionIsModified(Util.TestSources sources) {
        runTestMerge(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.BothModifiedSourceProvider.class)
    void mergeToTree_shouldReturnExpectedTree_whenBothVersionsAreModified(Util.TestSources sources)
            throws IOException {
        runTestMerge(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.CleanLineBasedFallbackProvider.class)
    void merge_shouldBeClean_withGranularLineBasedFallback(Util.TestSources sources)
            throws IOException {
        assertEquals(
                0, Spoon3dmMerge.INSTANCE.merge(sources.base, sources.left, sources.right).second);
    }

    @Disabled
    @ParameterizedTest
    @ArgumentsSource(Util.UnhandledInconsistencyProvider.class)
    void merge_shouldThrow_onUnhandledInconsistencies(Util.TestSources sources) {
        assertThrows(
                ConflictException.class,
                () -> Spoon3dmMerge.INSTANCE.merge(sources.base, sources.left, sources.right));
    }

    private static void runTestMerge(Util.TestSources sources) {
        CtModule expected = Parser.INSTANCE.parse(sources.expected);
        Object expectedImports = expected.getMetadata(Parser.IMPORT_STATEMENTS);
        Object expectedCuComment = expected.getMetadata(Parser.COMPILATION_UNIT_COMMENT);
        assert expectedImports != null;
        assert expectedCuComment != null;

        Pair<CtModule, Integer> merged =
                Spoon3dmMerge.INSTANCE.merge(sources.base, sources.left, sources.right);
        CtModule mergeTree = merged.first;
        Object mergedImports = mergeTree.getMetadata(Parser.IMPORT_STATEMENTS);
        Object mergedCuComment = mergeTree.getMetadata(Parser.COMPILATION_UNIT_COMMENT);

        // this assert is just to give a better overview of obvious errors, but it relies on the
        // pretty printer's
        // correctness
        // assertEquals(Cli.prettyPrint(expected), Cli.prettyPrint(mergeTree));
        System.out.println(Cli.prettyPrint(mergeTree));

        // these asserts are what actually matters
        assertEquals(expected, mergeTree);
        assertEquals(expectedImports, mergedImports);
        assertEquals(expectedCuComment, mergedCuComment);
    }
}
