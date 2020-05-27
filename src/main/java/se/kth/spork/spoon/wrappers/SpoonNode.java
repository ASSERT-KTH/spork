package se.kth.spork.spoon.wrappers;

import se.kth.spork.base3dm.ListNode;
import se.kth.spork.base3dm.Revision;
import spoon.reflect.declaration.CtElement;

import java.util.List;

/**
 * Interface describing a Spoon node as used in Spork. All concrete (i.e. non-virtual) {@link SpoonNode}s wrap a Spoon
 * {@link CtElement}. All nodes but list edges have at least the start/end of child list virtual nodes.
 *
 * @author Simon Larsén
 */
public interface SpoonNode extends ListNode {

    /**
     * @return The element wrapped by this node. Only legal to call on concrete nodes.
     * @throws UnsupportedOperationException If the node is not concrete.
     */
    CtElement getElement();

    /**
     * @return The parent of this node.
     * @throws UnsupportedOperationException If called on the virtual root.
     */
    SpoonNode getParent();

    /**
     * @return All virtual children belonging to this node.
     * @throws UnsupportedOperationException If called on a list edge.
     */
    List<SpoonNode> getVirtualNodes();

    /**
     * @return The start of this node's child list.
     * @throws UnsupportedOperationException If called on a list edge.
     */
    SpoonNode getStartOfChildList();

    /**
     * @return The end of this node's child list.
     * @throws UnsupportedOperationException If called on a list edge.
     */
    SpoonNode getEndOfChildList();
}
