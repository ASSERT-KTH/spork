package se.kth.spork.merge;

import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility methods for the test suite.
 *
 * @author Simon Larsén
 */
public class Util {
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
