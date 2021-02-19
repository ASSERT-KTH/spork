package se.kth.spork.base3dm

/**
 * Interface describing a PCS list node.
 *
 * @author Simon Larsén
 */
interface ListNode {
    /**
     * @return true iff this node is the dummy node at the start of a child list.
     */
    val isStartOfList: Boolean
        get() = false

    /**
     * @return true iff this node is the dummy node at the end of a child list.
     */
    val isEndOfList: Boolean
        get() = false

    /**
     * @return true iff this node is either the start or end dummy node of a child list.
     */
    val isListEdge: Boolean
        get() = isStartOfList || isEndOfList

    /**
     * @return true iff this node is a virtual node.
     */
    val isVirtual: Boolean
        get() = isListEdge

    /**
     * @return The revision this node was created from.
     * @throws UnsupportedOperationException If called on the virtual root.
     */
    val revision: Revision
}
