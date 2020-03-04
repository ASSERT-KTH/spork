package se.kth.spork;

import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.cli.SporkPrettyPrinter;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for the test suite.
 *
 * @author Simon Larsén
 */
public class Util {
    public static final Path CLEAN_MERGE_DIRPATH = Paths.get("src/test/resources/clean");
    public static final Path BOTH_MODIFIED_DIRPATH = CLEAN_MERGE_DIRPATH.resolve("both_modified");
    public static final Path LEFT_MODIFIED_DIRPATH = CLEAN_MERGE_DIRPATH.resolve("left_modified");
    public static final Path CONFLICT_DIRPATH = Paths.get("src/test/resources/conflict");

    public static String read(Path path) throws IOException {
        return String.join("\n", Files.readAllLines(path));
    }

    public static ITree toGumTree(String clazz) {
        CtClass<?> spoonTree = Launcher.parseClass(clazz);
        SpoonGumTreeBuilder builder = new SpoonGumTreeBuilder();
        return builder.getTree(spoonTree);
    }

    public static List<Conflict> parseConflicts(Path path) throws IOException {
        return parseConflicts(read(path));
    }

    public static List<Conflict> parseConflicts(String string) {
        Pattern conflictPattern = Pattern.compile(
                "(<<<<<<< LEFT.*?=======.*?>>>>>>> RIGHT)", Pattern.DOTALL);
        Matcher matcher = conflictPattern.matcher(string);

        List<Conflict> matches = new ArrayList<>();
        while (matcher.find()) {
            String match = matcher.group().trim();
            String[] parts = match.split("=======");
            assert parts.length == 2;

            String left = parts[0].replace(SporkPrettyPrinter.START_CONFLICT, "");
            String right = parts[1].replace(SporkPrettyPrinter.END_CONFLICT, "");
            Conflict conf = new Conflict();
            conf.left = left.trim();
            conf.right = right.trim();

            matches.add(conf);
        }
        return matches;
    }

    public static String keepLeftConflict(Path path) throws IOException {
        return keepLeftConflict(read(path));
    }

    /**
     * Take a string with conflicts and strip the conflict markers and the right revision,
     * keep the left revision.
     */
    public static String keepLeftConflict(String string) {
        Pattern rightConflictPattern = Pattern.compile(
                "=======.*?>>>>>>> RIGHT", Pattern.DOTALL);
        Matcher rightConflictMatcher = rightConflictPattern.matcher(string);
        String rightRevStrippend = rightConflictMatcher.replaceAll("");

        Pattern leftConflictMarkerPattern = Pattern.compile(SporkPrettyPrinter.START_CONFLICT);
        Matcher leftMarkerMatcher = leftConflictMarkerPattern.matcher(rightRevStrippend);
        return leftMarkerMatcher.replaceAll("");
    }

    public static class Conflict {
        String left;
        String right;

        @Override
        public String toString() {
            return "Conflict{" +
                    "left='" + left + '\'' +
                    ", right='" + right + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Conflict conflict = (Conflict) o;
            return Objects.equals(left, conflict.left) &&
                    Objects.equals(right, conflict.right);
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, right);
        }
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