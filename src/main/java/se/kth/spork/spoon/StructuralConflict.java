package se.kth.spork.spoon;

import spoon.reflect.declaration.CtElement;

import java.util.List;

/**
 * A simple class that provides some information on a structural conflict. Meant to be put as metadata on a conflict
 * "dummy node" using {@link StructuralConflict#STRUCTURAL_CONFLICT_METADATA_KEY} as the key.
 *
 * @author Simon Larsén
 */
public class StructuralConflict {
    public static final String STRUCTURAL_CONFLICT_METADATA_KEY = "SPORK_STRUCTURAL_CONFLICT";
    public final List<CtElement> left;
    public final List<CtElement> right;

    /**
     * @param left The left part of the conflict.
     * @param right The right part of the conflict.
     */
    public StructuralConflict(List<CtElement> left, List<CtElement> right) {
        this.left = left;
        this.right = right;
    }
}
