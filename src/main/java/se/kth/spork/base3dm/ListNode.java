package se.kth.spork.base3dm;

/**
 * Interface describing a PCS list node.
 *
 * @author Simon Larsén
 */
public interface ListNode {

    /**
     * @return true iff this node is the dummy node at the start of a child list.
     */
    default boolean isStartOfList() {
        return false;
    }

    /**
     * @return true iff this node is the dummy node at the end of a child list.
     */
    default boolean isEndOfList() {
        return false;
    }

    /**
     * @return true iff this node is either the start or end dummy node of a child list.
     */
    default boolean isListEdge() {
        return isStartOfList() || isEndOfList();
    }
}
