public class Sum {
    public int sumBetween(int a, int b) {
        checkBounds(a, b);

        int sum = 0;
        for (int i = a; i < b; i++) {
            sum += i;
        }
        return sum;
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

    public int sumAndMultiplyBetween(int a, int b) {
        int sum = sumBetween(a, b);
        int prod = multiplyBetween(a, b);
        return sum + prod;
    }
}
