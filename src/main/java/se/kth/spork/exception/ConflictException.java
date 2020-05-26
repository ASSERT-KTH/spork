package se.kth.spork.exception;

/**
 * Thrown when Spork encounters a conflict that cannot be handled properly.
 *
 * @author Simon Larsén
 */
public class ConflictException extends SporkException {
    public ConflictException(String s) {
        super(s);
    }

    public ConflictException(String s, Throwable throwable) {
        super(s, throwable);
    }
}
