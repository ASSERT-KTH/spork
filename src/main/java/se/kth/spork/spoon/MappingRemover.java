package se.kth.spork.spoon;

import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

/**
 * Utility class for removing a node, along with all of its descendants, from a {@link SpoonMapping} instance.
 *
 * @author Simon Larsén
 */
class MappingRemover extends CtScanner {
    private final SpoonMapping mapping;

    /**
     * Create a mapping remover for the provided mapping.
     *
     * @param mapping A mapping that this remover will operate on.
     */
    public MappingRemover(SpoonMapping mapping) {
        this.mapping = mapping;
    }

    /**
     * Remove this node and its associated virtual nodes from the mapping, and recursively remove all of its
     * descendants in the same way.
     *
     * @param node A node to remove from the mapping.
     */
    public void removeRelatedMappings(SpoonNode node) {
        CtElement elem = node.getElement();
        scan(elem);
    }

    @Override
    public void scan(CtElement element) {
        if (element == null) {
            return;
        }

        SpoonNode node = NodeFactory.wrap(element);

        mapping.remove(node);
        mapping.remove(NodeFactory.startOfChildList(node));
        mapping.remove(NodeFactory.startOfChildList(node));

        super.scan(element);
    }
}
