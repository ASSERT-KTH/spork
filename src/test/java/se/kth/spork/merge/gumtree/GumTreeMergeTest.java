package se.kth.spork.merge.gumtree;

import com.github.gumtreediff.tree.ITree;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.merge.Util;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static se.kth.spork.merge.Util.TestSources.fromTestDirectory;
import static se.kth.spork.merge.Util.toGumTree;

class GumTreeMergeTest {
    private static final Path cleanMergeDirpath = Paths.get("src/test/resources/clean");
    private static final Path leftModifiedDirpath = cleanMergeDirpath.resolve("left_modified");
    private static final Path bothModifiedDirpath = cleanMergeDirpath.resolve("both_modified");

    @ParameterizedTest
    @ValueSource(strings = {"add_parameter", "delete_method"})
    void mergeToTree_shouldReturnExpectedTree_whenLeftVersionIsModified(String testName) throws IOException{
        File testDir = leftModifiedDirpath.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"add_parameter", "delete_method"})
    void mergeToTree_shouldReturnExpectedTree_whenRightVersionIsModified(String testName) throws IOException{
        File testDir = leftModifiedDirpath.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);

        // swap left and right around to make this a "right modified" test case
        String left = sources.left;
        sources.left = sources.right;
        sources.right = left;

        runTestMerge(sources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"move_if", "delete_method", "add_same_method", "add_identical_elements_in_method"})
    void mergeToTree_shouldReturnExpectedTree_whenBothVersionsAreModified(String testName) throws IOException {
        File testDir = bothModifiedDirpath.resolve(testName).toFile();
        Util.TestSources sources = fromTestDirectory(testDir);
        runTestMerge(sources);
    }

    private static void runTestMerge(Util.TestSources sources) throws IOException {
        ITree expected = toGumTree(sources.expected);

        CtClass<?> base = Launcher.parseClass(sources.base);
        CtClass<?> left = Launcher.parseClass(sources.left);
        CtClass<?> right = Launcher.parseClass(sources.right);

        ITree merged = GumTreeMerge.mergeToTree(base, left, right);

        assertTrue(merged.isIsomorphicTo(expected));
    }
}