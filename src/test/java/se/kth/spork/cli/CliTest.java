package se.kth.spork.cli;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.Util;
import se.kth.spork.merge.spoon.Spoon3dmMerge;
import spoon.reflect.declaration.CtModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
    })
    void mergeTreeShouldEqualReParsedPrettyPrent_whenBothRevisionsAreModified(String testName, @TempDir Path tempDir) throws IOException {
        File testDir = Util.BOTH_MODIFIED_DIRPATH.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources, tempDir);
    }

    /**
     * Test the CLI by running merging the sources to a merge AST A, pretty printing A to a file and
     * parsing that file into a control tree B. If A and B are equal, no information has been lost in
     * the pretty print as far as Spoon knows (apart from import statements).
     *
     * TODO also check for import statements
     *
     * @param sources
     */
    private static void runTestMerge(Util.TestSources sources, Path tempDir) throws IOException {
        CtModule merged = (CtModule) Spoon3dmMerge.merge(sources.base, sources.left, sources.right);
        String expectedPrettyPrint = Cli.prettyPrint(merged);

        Path outFile = tempDir.resolve("Merge.java");
        Files.write(outFile, expectedPrettyPrint.getBytes(), StandardOpenOption.CREATE);

        CtModule reParsedMerge = Spoon3dmMerge.parse(outFile);
        String reParsedPrettyPRint = Cli.prettyPrint(reParsedMerge);

        // this assert is to give a better diff when there are obvious failures
        // it will most likely miss consistent errors in the pretty printer
        assertEquals(expectedPrettyPrint, reParsedPrettyPRint);

        // this assert is much more detailed, it should catch everything that exists inside the Spoon tree
        // that could potentially be lost in a pretty-print
        assertEquals(merged, reParsedMerge);

        // TODO add assert for import statements
    }

}