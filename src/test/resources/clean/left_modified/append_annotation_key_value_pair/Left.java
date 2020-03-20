import org.junit.jupiter.api.Test;

class Cls {

    @Test(timeout = 3000, otherValue = "hello")
    void testSomething() {
        throw new IllegalStateException();
    }
}
