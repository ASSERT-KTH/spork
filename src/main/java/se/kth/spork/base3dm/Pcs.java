package se.kth.spork.base3dm;

import java.util.Objects;

/**
 * Representation of a Parent/Child/Successor triple for 3DM merge. Note that only root, predecessor and successor
 * values affect hashing and equality.
 *
 * @author Simon Larsén
 */
public class Pcs<T extends ListNode> {
    private T root;
    private T predecessor;
    private T successor;
    private Revision revision;

    /**
     * @param root The root of this PCS.
     * @param predecessor The predecessor (or child) of this PCS.
     * @param successor The successor of this PCS.
     * @param revision The revision this PCS is related to.
     */
    public Pcs(T root, T predecessor, T successor, Revision revision) {
        this.root = root;
        this.predecessor = predecessor;
        this.successor = successor;
        this.revision = revision;
    }

    @Override
    public String toString() {
        return "PCS(" + (revision != null ? revision + "," : "")
                + root.toString() + ","
                + predecessor.toString() + ","
                + successor.toString() + ")";
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
        return Objects.hash(root, predecessor, successor);
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