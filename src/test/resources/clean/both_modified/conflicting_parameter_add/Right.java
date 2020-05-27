/**
 * This parameter add is technically conflicting, but should be automatically resolved with an optimistic conflict
 * handler.
 */
public class Adder {
    public int add(int a, int b, int c) {
        return a + b;
    }
}
