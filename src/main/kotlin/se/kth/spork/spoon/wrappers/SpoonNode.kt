package se.kth.spork.spoon.wrappers

import se.kth.spork.base3dm.ListNode
import spoon.reflect.declaration.CtElement

/**
 * Interface describing a Spoon node as used in Spork. All concrete (i.e. non-virtual) [SpoonNode]s wrap a Spoon
 * [CtElement]. All nodes but list edges have at least the start/end of child list virtual nodes.
 *
 * @author Simon Larsén
 */
interface SpoonNode : ListNode {
    /**
     * @return The element wrapped by this node. Only legal to call on concrete nodes.
     * @throws UnsupportedOperationException If the node is not concrete.
     */
    val element: CtElement

    /**
     * @return The parent of this node.
     * @throws UnsupportedOperationException If called on the virtual root.
     */
    val parent: SpoonNode

    /**
     * @return All virtual children belonging to this node.
     * @throws UnsupportedOperationException If called on a list edge.
     */
    val virtualNodes: List<SpoonNode>

    /**
     * @return The start of this node's child list.
     * @throws UnsupportedOperationException If called on a list edge.
     */
    val startOfChildList: SpoonNode

    /**
     * @return The end of this node's child list.
     * @throws UnsupportedOperationException If called on a list edge.
     */
    val endOfChildList: SpoonNode
}