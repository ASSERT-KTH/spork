package se.kth.spork.spoon;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import se.kth.spork.Util;
import se.kth.spork.cli.Cli;
import spoon.reflect.declaration.*;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

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
    void mergeToTree_shouldReturnExpectedTree_whenBothVersionsAreModified(Util.TestSources sources) throws IOException {
        runTestMerge(sources);
    }

    private static void runTestMerge(Util.TestSources sources) {
        CtModule expected = Parser.parse(sources.expected);
        Object expectedImports = expected.getMetadata(Parser.IMPORT_STATEMENTS);
        assert expectedImports != null;

        CtModule merged = (CtModule) Spoon3dmMerge.merge(sources.base, sources.left, sources.right);
        Object mergedImports = merged.getMetadata(Parser.IMPORT_STATEMENTS);

        // FIXME There should be no need to sort output elements, but resolutions to ordering conflicts can cause strange method ordering
        Compare.sortUnorderedElements(expected);
        Compare.sortUnorderedElements(merged);

        // this assert is just to give a better overview of obvious errors, but it relies on the pretty printer's
        // correctness
        assertEquals(Cli.prettyPrint(expected), Cli.prettyPrint(merged));

        // these asserts are what actually matters
        assertEquals(expected, merged);
        assertEquals(expectedImports, mergedImports);
    }
}

