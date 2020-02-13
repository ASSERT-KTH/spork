public class Sum {
    public int sumBetween(int a, int b) {
        checkBounds(a, b);
        System.out.println("calculating sum from " + a + " to " + b);

        int sum = 0;
        for (int i = a; i < b; i++) {
            sum += i;
        }
        return sum;
    }

    public void checkBounds(int a, int b) {
        if (b <= a) {
            throw new IllegalArgumentException("b must be greater than or equal to a");
        }
        if (a < 0) {
            // this will be a strange sum
            throw new IllegalArgumentException("a must be at least 0");
        }
    }
}
