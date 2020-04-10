package se.kth.spork.spoon;

import se.kth.spork.base3dm.Pcs;
import spoon.reflect.declaration.CtElement;

import java.util.List;
import java.util.Objects;

/**
 * A simple class that provides some information on a structural conflict. Meant to be put as metadata on a conflict
 * "dummy node" using {@link StructuralConflict#METADATA_KEY} as the key.
 *
 * @author Simon Larsén
 */
public class StructuralConflict {
    public static final String METADATA_KEY = "SPORK_STRUCTURAL_CONFLICT";
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

    public static boolean isRootConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getRoot(), right.getRoot()) &&
                (Objects.equals(left.getPredecessor(), right.getPredecessor()) ||
                        Objects.equals(left.getSuccessor(), right.getSuccessor()));
    }

    public static boolean isPredecessorConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getPredecessor(), right.getPredecessor()) &&
                Objects.equals(left.getSuccessor(), right.getSuccessor()) &&
                Objects.equals(left.getRoot(), right.getRoot());
    }

    public static boolean isSuccessorConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getSuccessor(), right.getSuccessor()) &&
                Objects.equals(left.getPredecessor(), right.getPredecessor()) &&
                Objects.equals(left.getRoot(), right.getRoot());
    }
}
