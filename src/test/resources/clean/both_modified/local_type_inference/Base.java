import java.util.Arrays;
import java.util.stream.IntStream;

class Cls {
    public static int sum(int... values) {
        IntStream stream = Arrays.stream(values);
        return stream.reduce((int a, int b) -> a + b);
    }
}