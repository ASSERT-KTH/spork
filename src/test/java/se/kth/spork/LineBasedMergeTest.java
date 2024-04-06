package se.kth.spork;

import static org.junit.jupiter.api.Assertions.assertEquals;

import kotlin.Pair;
import org.junit.jupiter.api.Test;
import se.kth.spork.util.LineBasedMergeKt;

public class LineBasedMergeTest {

    @Test
    public void testMergeWithDeletionsMinimal() {
        Pair<String, Integer> result =
                LineBasedMergeKt.lineBasedMerge("a\nB\nC\nD\ne", "a\nB\nC\ne", "a\nB\nD\ne");

        assertEquals(
                "a\n"
                        + "B\n"
                        + "<<<<<<< LEFT\n"
                        + "C\n"
                        + "=======\n"
                        + "D\n"
                        + ">>>>>>> RIGHT\n"
                        + "e",
                result.component1());
    }
}
