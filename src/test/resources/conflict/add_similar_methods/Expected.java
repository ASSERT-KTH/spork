public class Arithmetic {
    public int div(int a, int b) {
<<<<<<< LEFT
=======
        if (b == 0) {
            throw new IllegalArgumentException("b must be non-zero");
        }
>>>>>>> RIGHT
        return a / b;
    }

    public int add(int a, int b) {
        return a + b;
    }

    public int sub(int a, int b) {
        return a - b;
    }
}