package se.kth.spork.merge.spoon;

import gumtree.spoon.AstComparator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Util;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.support.sniper.SniperJavaPrettyPrinter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpoonPcsTest {
    private static final Path testDirpath = Paths.get("src/test/resources/standalone");

    @ParameterizedTest
    @ValueSource(strings = {"Adder", "Sum", "ArrayList", "Tree"})
    void fromSpoon_shouldBuildConsistentPcs(String testName) throws Exception {
        String filename = testName + ".java";
        String fileContents = Util.read(testDirpath.resolve(filename));
        CtClass<?> cls = Launcher.parseClass(fileContents);

        TreeScanner scanner = new TreeScanner();
        scanner.scan(cls);
        Set<Pcs<CtElement>> pcses = scanner.getPcses();

        CtClass<?> rebuilt = SpoonPcs.fromPcs(pcses);

        System.out.println(cls);
        System.out.println(rebuilt);

        assertEquals(rebuilt, cls);
        Iterator<CtElement> origCt = cls.descendantIterator();
        Iterator<CtElement> rebuiltCt = rebuilt.descendantIterator();
        while (origCt.hasNext()) {
            CtElement fromOrig = origCt.next();
            CtElement fromRebuilt = rebuiltCt.next();
            assert(fromOrig != fromRebuilt);
        }
    }


}