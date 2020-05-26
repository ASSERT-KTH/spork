package se.kth.spork.exception;

/**
 * Generic exception thrown when there is a critical error in the merge.
 *
 * @author Simon Larsén
 */
public class MergeException extends SporkException {
    public MergeException(String s) {
        super(s);
    }

    public MergeException(String s, Throwable throwable) {
        super(s, throwable);
    }
}
