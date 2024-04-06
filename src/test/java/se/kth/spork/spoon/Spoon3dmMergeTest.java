package se.kth.spork.spoon;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kotlin.Pair;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import se.kth.spork.Util;
import se.kth.spork.cli.Cli;
import se.kth.spork.exception.ConflictException;
import spoon.reflect.declaration.*;

class Spoon3dmMergeTest {

    @ParameterizedTest
    @ArgumentsSource(Util.LeftModifiedSourceProvider.class)
    void mergeToTree_shouldReturnExpectedTree_whenLeftVersionIsModified(Util.TestSources sources)
            throws IOException {
        runTestMerge(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(Util.RightModifiedSourceProvider.class)
    void mergeToTree_shouldReturnExpectedTree_whenRightVersionIsModified(Util.TestSources sources)
            throws IOException {
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
                0,
                Spoon3dmMerge.INSTANCE
                        .merge(sources.base, sources.left, sources.right)
                        .getSecond());
    }

    @Disabled
    @ParameterizedTest
    @ArgumentsSource(Util.UnhandledInconsistencyProvider.class)
    void merge_shouldThrow_onUnhandledInconsistencies(Util.TestSources sources) {
        assertThrows(
                ConflictException.class,
                () -> Spoon3dmMerge.INSTANCE.merge(sources.base, sources.left, sources.right));
    }

    private static void runTestMerge(Util.TestSources sources) throws IOException {
        CtModule expected = Parser.INSTANCE.parse(sources.expected);
        Object expectedImports = expected.getMetadata(Parser.IMPORT_STATEMENTS);
        Object expectedCuComment = expected.getMetadata(Parser.COMPILATION_UNIT_COMMENT);
        assert expectedImports != null;
        assert expectedCuComment != null;

        Pair<CtModule, Integer> merged =
                Spoon3dmMerge.INSTANCE.merge(sources.base, sources.left, sources.right);
        CtModule mergeTree = merged.getFirst();
        Object mergedImports = mergeTree.getMetadata(Parser.IMPORT_STATEMENTS);
        Object mergedCuComment = mergeTree.getMetadata(Parser.COMPILATION_UNIT_COMMENT);

        // we cannot assert CtModules so stricly
        // because the order of types is taken into account in the EqualityVisitor of Spoon
        // assertEquals(expected, mergeTree);

        // so we force the order of types and assert one by one
        assertTrue(expected instanceof CtModule);
        final Comparator<CtType> nameComparator =
                new Comparator<>() {
                    @Override
                    public int compare(CtType o, CtType t1) {
                        return o.getQualifiedName().compareTo(t1.getQualifiedName());
                    }
                };
        final List<CtType> list1 = expected.filterChildren(c -> (c instanceof CtType)).list();
        final List<CtType> list2 = mergeTree.filterChildren(c -> (c instanceof CtType)).list();
        list1.sort(nameComparator);
        list2.sort(nameComparator);

        // Only print differences to stdout if the test fails
        if (!Objects.equals(list1, list2)
                || !Objects.equals(expectedImports, mergedImports)
                || !Objects.equals(expectedCuComment, mergedCuComment)) {
            System.out.println("~~~~ Expected ~~~~");
            System.out.println(Files.readString(sources.expected));
            System.out.println("~~~~ Actual ~~~~");
            System.out.println(Cli.prettyPrint(mergeTree));
        }

        assertEquals(list1, list2);
        assertEquals(expectedImports, mergedImports);
        assertEquals(expectedCuComment, mergedCuComment);
    }
}
