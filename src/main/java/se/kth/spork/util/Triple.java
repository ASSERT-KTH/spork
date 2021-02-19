package se.kth.spork.util;

/**
 * A simple generic container for three values of potentially different types.
 *
 * @author Simon Larsén
 */
public class Triple<T,K,V> {
    public final T first;
    public final K second;
    public final V third;

    public Triple(T first, K second, V third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public static <T,K,V> Triple<T,K,V> of(T first, K second, V third) {
        return new Triple<>(first, second, third);
    }
}
