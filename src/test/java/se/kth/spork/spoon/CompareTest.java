package se.kth.spork.spoon;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import spoon.reflect.declaration.CtModule;

class CompareTest {
    private static final Path EQUAL_DIR_PATH = Paths.get("src/test/resources/compare/equal");
    private static final Path UNEQUAL_DIR_PATH = Paths.get("src/test/resources/compare/unequal");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jumbled_method_order",
                "fields_moved_between_methods",
                "jumbled_nested_classes_and_methods",
            })
    void compareUnnamedModules_shouldBeTrue_whenModulesAreEqual(String testName) {
        Path testDir = EQUAL_DIR_PATH.resolve(testName);
        Path left = testDir.resolve("Left.java");
        Path right = testDir.resolve("Right.java");

        CtModule leftMod = Parser.parse(left);
        CtModule rightMod = Parser.parse(right);

        boolean equal = Compare.compare(leftMod, rightMod);

        assertTrue(equal);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jumbled_field_order",
                "package_mismatch",
                "completely_different_files",
            })
    void compareUnnamedModules_shouldBeFalse_whenModulesAreUnequal(String testName) {
        Path testDir = UNEQUAL_DIR_PATH.resolve(testName);
        Path left = testDir.resolve("Left.java");
        Path right = testDir.resolve("Right.java");

        CtModule leftMod = Parser.parse(left);
        CtModule rightMod = Parser.parse(right);

        boolean equal = Compare.compare(leftMod, rightMod);

        assertFalse(equal);
    }
}
