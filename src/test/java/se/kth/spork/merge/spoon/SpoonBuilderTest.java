package se.kth.spork.merge.spoon;

import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class SpoonBuilderTest {
    private static final Path testDirpath = Paths.get("src/test/resources/standalone");

    @ParameterizedTest
    @ValueSource(strings = {"Adder", "Sum"})
    void buildTree_shouldBuildCorrectSpoonTree(String testName) throws IOException {
        String fileName = testName + ".java";
        String fileContents = read(testDirpath.resolve(fileName));
        CtClass<?> initial = Launcher.parseClass(fileContents);

        ITree gumTree = new SpoonGumTreeBuilder().getTree(initial);

        CtClass<?> rebuilt = new SpoonBuilder().buildSpoonTree(gumTree);

        assertEquals(initial.toString(), rebuilt.toString());
    }

    private static String read(Path path) throws IOException {
        return String.join("\n", Files.readAllLines(path));
    }
}