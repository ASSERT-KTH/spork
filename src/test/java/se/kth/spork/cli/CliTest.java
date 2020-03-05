package se.kth.spork.cli;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.Util;
import se.kth.spork.spoon.Parser;
import se.kth.spork.spoon.Spoon3dmMerge;
import spoon.reflect.declaration.CtModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static se.kth.spork.Util.TestSources.fromTestDirectory;

class CliTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "add_parameter",
            "delete_method",
            "add_if_block",
            "delete_if_block",
            "rename_method",
            "rename_class",
            "change_declared_type",
            "rename_variable",
            "rename_parameter",
            "rename_type_parameter",
            "rename_interface",
            "add_nested_class",
            "add_package_private_class",
            "rename_enum",
            "edit_annotations",
            "change_package_statement",
            "generify_method",
            "add_class_visibility",
            "change_field_modifiers",
    })
    void mergeTreeShouldEqualReParsedPrettyPrint_whenLeftIsModified(
            String testName, @TempDir Path tempDir) throws IOException {
        File testDir = Util.LEFT_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources, tempDir);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "add_parameter",
            "delete_method",
            "add_if_block",
            "delete_if_block",
            "rename_method",
            "rename_class",
            "change_declared_type",
            "rename_variable",
            "rename_parameter",
            "rename_type_parameter",
            "rename_interface",
            "add_nested_class",
            "add_package_private_class",
            "rename_enum",
            "edit_annotations",
            "change_package_statement",
            "generify_method",
            "add_class_visibility",
            "change_field_modifiers",
    })
    void mergeTreeShouldEqualReParsedPrettyPrint_whenRightIsModified(
            String testName, @TempDir Path tempDir) throws IOException {
        File testDir = Util.LEFT_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources, tempDir);
    }


    @ParameterizedTest
    @ValueSource(strings = {
            "move_if",
            "delete_method",
            "add_same_method",
            "add_identical_elements_in_method",
            "add_parameter",
            "add_import_statements",
            "change_binops",
            "change_unary_ops",
            "add_field_modifiers",
            "method_method_ordering_conflict",
            "multiple_method_ordering_conflicts",
    })
    void mergeTreeShouldEqualReParsedPrettyPrent_whenBothRevisionsAreModified(String testName, @TempDir Path tempDir) throws IOException {
        File testDir = Util.BOTH_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources, tempDir);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "single_conflicting_statement",
            "multiple_conflicting_statements",
            "multiple_simple_conflicts",
            "conflicting_variable_rename",
            "conflicting_type_change",
    })
    void prettyPrint_shouldContainConflict(String testName, @TempDir Path tempDir) throws IOException {
        File testDir = Util.CONFLICT_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        List<Util.Conflict> expectedConflicts = Util.parseConflicts(sources.expected);

        CtModule merged = (CtModule) Spoon3dmMerge.merge(sources.base, sources.left, sources.right);

        String prettyPrint = Cli.prettyPrint(merged);

        List<Util.Conflict> actualConflicts = Util.parseConflicts(prettyPrint);

        assertEquals(expectedConflicts, actualConflicts);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "single_conflicting_statement",
            "multiple_conflicting_statements",
            "multiple_simple_conflicts",
            "conflicting_variable_rename",
            "conflicting_type_change",
    })
    void prettyPrint_shouldParseToExpectedTree_whenConflictHasBeenStrippedOut(String testName, @TempDir Path tempDir) throws IOException {
        File testDir = Util.CONFLICT_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
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