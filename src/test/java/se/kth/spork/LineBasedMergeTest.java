package se.kth.spork;

import static org.junit.jupiter.api.Assertions.assertEquals;

import kotlin.Pair;
import org.junit.jupiter.api.Test;
import se.kth.spork.util.LineBasedMergeKt;

public class LineBasedMergeTest {
    @Test
    public void testSimpleMerge() {
        Pair<String, Integer> result =
                LineBasedMergeKt.lineBasedMerge(
                        "hello world", "ola mundo", "bonjour le monde", true);

        assertEquals(
                result.component1(),
                "<<<<<<< LEFT\n"
                        + "ola mundo\n"
                        + "||||||| BASE\n"
                        + "hello world\n"
                        + "=======\n"
                        + "bonjour le monde\n"
                        + ">>>>>>> RIGHT");
        assertEquals(result.component2(), 1);
    }
}
