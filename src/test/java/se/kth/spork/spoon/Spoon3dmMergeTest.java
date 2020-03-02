package se.kth.spork.spoon;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.Util;
import se.kth.spork.cli.Cli;
import spoon.reflect.declaration.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static se.kth.spork.Util.TestSources.fromTestDirectory;

class Spoon3dmMergeTest {

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
    })
    void mergeToTree_shouldReturnExpectedTree_whenLeftVersionIsModified(String testName) {
        File testDir = Util.LEFT_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources);
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
    })
    void mergeToTree_shouldReturnExpectedTree_whenRightVersionIsModified(String testName) {
        File testDir = Util.LEFT_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);

        // swap left and right around to make this a "right modified" test case
        Path left = sources.left;
        sources.left = sources.right;
        sources.right = left;

        runTestMerge(sources);
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
    })
    void mergeToTree_shouldReturnExpectedTree_whenBothVersionsAreModified(String testName) throws IOException {
        File testDir = Util.BOTH_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources);
    }

    private static void runTestMerge(Util.TestSources sources) {
        CtElement expected = Parser.parse(sources.expected);
        Object expectedImports = expected.getMetadata(Parser.IMPORT_STATEMENTS);
        assert expectedImports != null;

        CtElement merged = Spoon3dmMerge.merge(sources.base, sources.left, sources.right);
        Object mergedImports = merged.getMetadata(Parser.IMPORT_STATEMENTS);

        assertEquals(expected, merged);
        assertEquals(expectedImports, mergedImports);
    }
}

