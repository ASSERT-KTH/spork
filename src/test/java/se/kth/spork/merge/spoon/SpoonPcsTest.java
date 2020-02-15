package se.kth.spork.merge.spoon;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.Util;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpoonPcsTest {
    private static final Path testDirpath = Paths.get("src/test/resources/standalone");

    /**
     *  Test that converting a Spoon Tree A into a PCS tree P, and then converting P into a
     *  Spoon tree B has the two following properties:
     *
     *  <code>A.equals(B)</code> and for each pair of nodes <code>(a,b)</code> in A and B,
     *  we have that <code>a != b</code> (i.e. different objects).
     */
    @ParameterizedTest
    @ValueSource(strings = {"Adder", "Sum", "ArrayList", "Tree"})
    void testSpoonPcsRoundTrip(String testName) throws Exception {
        String filename = testName + ".java";
        String fileContents = Util.read(testDirpath.resolve(filename));
        CtClass<?> cls = Launcher.parseClass(fileContents);

        Set<Pcs<CtWrapper>> pcses = SpoonPcs.fromSpoon(cls, Revision.BASE);

        CtClass<?> rebuilt = SpoonPcs.fromPcs(pcses);

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