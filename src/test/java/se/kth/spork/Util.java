package se.kth.spork;

import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility methods for the test suite.
 *
 * @author Simon Larsén
 */
public class Util {
    public static final Path CLEAN_MERGE_DIRPATH = Paths.get("src/test/resources/clean");
    public static final Path BOTH_MODIFIED_DIRPATH = CLEAN_MERGE_DIRPATH.resolve("both_modified");
    public static final Path LEFT_MODIFIED_DIRPATH = CLEAN_MERGE_DIRPATH.resolve("left_modified");

    public static String read(Path path) throws IOException {
        return String.join("\n", Files.readAllLines(path));
    }

    public static ITree toGumTree(String clazz) {
        CtClass<?> spoonTree = Launcher.parseClass(clazz);
        SpoonGumTreeBuilder builder = new SpoonGumTreeBuilder();
        return builder.getTree(spoonTree);
    }

    public static class TestSources {
        public Path base;
        public Path left;
        public Path right;
        public Path expected;

        TestSources(Path base, Path left, Path right, Path expected) {
            this.base = base;
            this.left = left;
            this.right = right;
            this.expected = expected;
        }


        public static TestSources fromTestDirectory(File testDir) {
            Path path = testDir.toPath();
            return new TestSources(
                    path.resolve("Base.java"),
                    path.resolve("Left.java"),
                    path.resolve("Right.java"),
                    path.resolve("Expected.java")
            );
        }
    }
}
