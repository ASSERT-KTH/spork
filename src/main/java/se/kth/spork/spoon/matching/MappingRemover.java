package se.kth.spork.spoon.matching;

import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.spoon.wrappers.NodeFactory;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.Set;

/**
 * Utility class for removing a node, along with all of its descendants, from a {@link SpoonMapping} instance.
 *
 * @author Simon Larsén
 */
public class MappingRemover extends CtScanner {
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
     * Remove the provided nodes from the mappings, along with all of their descendants and any associated virtual
     * nodes. This is a method of allowing certain forms of conflicts to pass by, such as root conflicts. By removing
     * the mapping, problems with duplicated nodes is removed.
     *
     * @param nodes A set of nodes to remove from the mappings.
     * @param baseLeft A base-to-left mapping.
     * @param baseRight A base-to-right mapping.
     * @param leftRight A left-to-right mapping.
     */
    public static void
    removeFromMappings(Set<SpoonNode> nodes, SpoonMapping baseLeft, SpoonMapping baseRight, SpoonMapping leftRight) {
        MappingRemover baseLeftMappingRemover = new MappingRemover(baseLeft);
        MappingRemover baseRightMappingRemover = new MappingRemover(baseRight);
        MappingRemover leftRightMappingRemover = new MappingRemover(leftRight);

        for (SpoonNode node : nodes) {
            switch (node.getRevision()) {
                case BASE:
                    leftRightMappingRemover.removeRelatedMappings(baseLeft.getDst(node));
                    baseLeftMappingRemover.removeRelatedMappings(node);
                    baseRightMappingRemover.removeRelatedMappings(node);
                    break;
                case LEFT:
                    baseLeftMappingRemover.removeRelatedMappings(node);
                    leftRightMappingRemover.removeRelatedMappings(node);
                    break;
                case RIGHT:
                    baseRightMappingRemover.removeRelatedMappings(node);
                    leftRightMappingRemover.removeRelatedMappings(node);
                    break;
            }
        }
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
        mapping.remove(node.getStartOfChildList());
        mapping.remove(node.getStartOfChildList());

        super.scan(element);
    }
}
