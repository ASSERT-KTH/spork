package se.kth.spork.exception;

/**
 * Base exception for Spork exceptions.
 *
 * @author Simon Larsén
 */
public abstract class SporkException extends RuntimeException {
    public SporkException(String s) {
        super(s);
    }

    public SporkException(String s, Throwable throwable) {
        super(s, throwable);
    }
}
