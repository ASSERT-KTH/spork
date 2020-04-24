public class Sum {
    public int sumBetween(int a, int b) {
        checkBounds(a, b);

        int sum = 0;
        for (int i = a; i < b; i++) {
            sum += i;
        }
        return sum;
    }

    private int sumTo(int to) {
        checkToBound(to);
        int sum = 0;
        for (int i = 0; i < to; i++) {
            sum += i;
        }
        return sum;
    }

    private void checkToBound(int to) {
        if (to >= 1_000) {
            throw new IllegalArgumentException("I can't count that high: " + to);
        }
    }

    private int multiplyBetween(int a, int b) {
        checkBounds(a, b);

        int prod = 1;
        for (int i = a; i < b; i++) {
            prod *= i;
        }

        return prod;
    }

    private void checkBounds(int a, int b) {
        if (b <= a) {
            throw new IllegalArgumentException("b must be greater than or equal to a");
        }
    }

    public int sumBetweenUndirected(int a, int b) {
        return a <= b ? sumBetween(a, b) : sumBetween(b, a);
    }

    public int sumAndMultiplyBetween(int a, int b) {
        int sum = sumBetween(a, b):
        int prod = multiplyBetween(a, b);
        return sum + prod;
    }
}
