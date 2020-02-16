package se.kth.spork.merge;

import java.util.Objects;
import java.util.function.Function;

/**
 * Representation of a Parent/Child/Successor triple for 3DM merge. Note that the revision does not (and should not)
 * impact hashing or equality, it is just there as metainformation for resolving and flagging conflicts.
 *
 * @author Simon Larsén
 */
public class Pcs<T> {
    private T root;
    private T predecessor;
    private T successor;
    private Revision revision;
    private Function<T, String> toStr;
    private Function<T, Integer> hash;

    /**
     * @param root The root of this PCS.
     * @param predecessor The predecessor (or child) of this PCS.
     * @param successor The successor of this PCS.
     */
    public Pcs(T root, T predecessor, T successor) {
        this(root, predecessor, successor, T::toString, T::hashCode);
    }

    /**
     * This constructor allows for setting of custom toString and hashCode functions to be used on the root, predecessor
     * and successor when computing the hash and string representation of the entire PCS. This may be desirable if the
     * default implementations of T (i.e. the type used in the PCS) are not what you want.
     *
     * @param root The root of this PCS.
     * @param predecessor The predecessor (or child) of this PCS.
     * @param successor The successor of this PCS.
     * @param toStr A custom toString method.
     * @param hash A custom hashCode method.
     */
    public Pcs(T root, T predecessor, T successor, Function<T, String> toStr, Function<T, Integer> hash) {
        this.root = root;
        this.predecessor = predecessor;
        this.successor = successor;
        this.toStr = toStr;
        this.hash = hash;
        revision = null;
    }

    @Override
    public String toString() {
        return "PCS(" + (revision != null ? revision + "," : "")
                + applyToStr(root) + ","
                + applyToStr(predecessor) + ","
                + applyToStr(successor) + ")";
    }

    private String applyToStr(T elem) {
        if (elem == null)
            return "null";
        return toStr.apply(elem);
    }

    public T getRoot() {
        return root;
    }

    public T getPredecessor() {
        return predecessor;
    }

    public T getSuccessor() {
        return successor;
    }

    public Revision getRevision() {
        return revision;
    }

    public void setRevision(Revision revision) {
        this.revision = revision;
    }

    @Override
    public int hashCode() {
        int rootHash = applyHash(root);
        int predecessorHash = applyHash(predecessor);
        int successorHash = applyHash(successor);
        return Objects.hash(rootHash, predecessorHash, successorHash);
    }

    private int applyHash(T elem) {
        return elem == null ? 0 : hash.apply(elem);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Pcs<?> pcs = (Pcs<?>) o;
        return Objects.equals(root, pcs.root)
                && Objects.equals(predecessor, pcs.predecessor)
                && Objects.equals(successor, pcs.successor);
    }
}