package se.kth.spork.merge;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Representation of a merge conflict.
 *
 * @author Simon Larsén
 */
public class Conflict<T> {
    private final T[] conflicting;

    @SafeVarargs
    public Conflict(T... conflicting) {
        this.conflicting = conflicting;
    }

    @Override
    public String toString() {
        return "Conflict{" + Arrays.stream(conflicting)
                .map(T::toString)
                .sorted()
                .collect(Collectors.joining(", ")) + '}';
    }
}
