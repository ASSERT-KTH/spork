class Cls {
    public static void add(int a, int... b) {
        int sum = 0;
        for (int val : b) {
            sum += val;
        }
        return a + sum;
    }
}