package se.kth.spork.merge;

import java.util.List;

/**
 * A visitor class for traversing a PCS structure. It defines two methods, one for a "regular" visit, and one for
 * visiting a conflicting node.
 *
 * @author Simon Larsén
 */
public interface PcsVisitor<T> {

    /**
     * Visit a node.
     *
     * @param parent Parent node of the current node.
     * @param node The current node being visited.
     */
    void visit(T parent, T node);

    /**
     * Visit the roots of conflicting nodes. Upon being called, the PcsVisitor is responsible for
     * noting that a conflict has started, if that is important. When the caller deems that the conflict
     * is fully resolved, the {@link PcsVisitor#endConflict()} method should be called to let the PcsVisitor
     * know that the conflict is done with.
     *
     * Note that the caller is responsible for calling the {@link PcsVisitor#visit(T, T)} method on the conflicting
     * nodes' children.
     *
     * @param parent Parent node of the nodes in conflict.
     * @param left Left revision's root nodes in the conflict.
     * @param right Right revision's root nodes in the conflict.
     */
    void visitConflicting(T parent, List<T> left, List<T> right);

    /**
     * Lets the PcsVisitor know that the ongoing conflict has been fully resolved, and all children of conflicting
     * nodes have been visited.
     */
    void endConflict();
}
