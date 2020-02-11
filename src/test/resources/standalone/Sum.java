public class Sum {
    public int sumBetween(int a, int b) {
        if (b <= a) {
            throw new IllegalArgumentException("a must be smaller than b");
        } else {
            int sum = 0;
            for (int i = a, j = 2; i < b; i++) {
                sum += 1;
                sum += i;
            }
            return sum;
        }
    }
}
