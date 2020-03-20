import org.junit.jupiter.api.Test;

class Cls {

    @Test(timeout = 3000)
    void testSomething() {
        throw new IllegalStateException();
    }
}