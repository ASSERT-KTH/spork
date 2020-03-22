import org.junit.jupiter.api.Test;

class Cls {
    @Test(expected = IllegalArgumentException.class)
    void testSomething() {
        Class<?> clazz = null;
    }
}
