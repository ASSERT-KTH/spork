class Cls {
    public static void add(int... b) {
        int sum = 0;
        for (int val : b) {
            sum += val;
        }
        return sum;
    }
}