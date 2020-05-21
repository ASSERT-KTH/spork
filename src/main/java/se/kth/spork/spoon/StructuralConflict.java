package se.kth.spork.spoon;

import se.kth.spork.base3dm.Pcs;
import se.kth.spork.spoon.wrappers.SpoonNode;
import spoon.reflect.declaration.CtElement;

import java.util.*;

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

    public boolean isUnresolved() {
        return left.isEmpty() && right.isEmpty();
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

    public static Set<SpoonNode>
    extractRootConflictingNodes(Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts) {
        Set<SpoonNode> toIgnore = new HashSet<>();

        for (Map.Entry<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> entry : structuralConflicts.entrySet()) {
            Pcs<SpoonNode> pcs = entry.getKey();
            for (Pcs<SpoonNode> other : entry.getValue()) {
                if (isRootConflict(pcs, other)) {
                    if (pcs.getPredecessor().equals(other.getPredecessor())) {
                        toIgnore.add(other.getPredecessor());
                    }
                    if (pcs.getSuccessor().equals(other.getSuccessor())) {
                        toIgnore.add(other.getSuccessor());
                    }
                }
            }
        }
        return toIgnore;
    }
}
