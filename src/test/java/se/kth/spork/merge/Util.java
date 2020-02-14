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
        public String base;
        public String left;
        public String right;
        public String expected;

        TestSources(String base, String left, String right, String expected) {
            this.base = base;
            this.left = left;
            this.right = right;
            this.expected = expected;
        }


        public static TestSources fromTestDirectory(File testDir) throws IOException {
            Path path = testDir.toPath();
            return new TestSources(
                    read(path.resolve("Base.java")),
                    read(path.resolve("Left.java")),
                    read(path.resolve("Right.java")),
                    read(path.resolve("Expected.java"))
            );
        }
    }
}
