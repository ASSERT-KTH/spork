package se.kth.spork.util;

/**
 * A simple, generic container for two arbitrary elements.
 *
 * @author Simon Larsén
 */
public class Pair<T,E> {
    public final T first;
    public final E second;

    public Pair(T first, E second) {
        this.first = first;
        this.second = second;
    }
}
