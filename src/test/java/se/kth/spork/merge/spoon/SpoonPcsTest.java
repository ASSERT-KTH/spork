package se.kth.spork.merge.spoon;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Util;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.path.CtRole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

class SpoonPcsTest {
    private static final Path testDirpath = Paths.get("src/test/resources/standalone");

    @ParameterizedTest
    @ValueSource(strings = {"Adder", "Sum", "ArrayList"})
    void fromSpoon_shouldBuildConsistentPcs(String testName) throws IOException {
        String filename = testName + ".java";
        String fileContents = Util.read(testDirpath.resolve(filename));
        CtClass<?> cls = Launcher.parseClass(fileContents);

        Set<Pcs<CtElement>> pcses = SpoonPcs.fromSpoon(cls);



        CtClass<?> rebuilt = SpoonPcs.fromPcs(pcses);

        boolean equal = rebuilt.equals(cls);


        System.out.println(rebuilt);

        System.out.println(equal);
    }


}