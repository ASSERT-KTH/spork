package se.kth.spork.spoon.matching

import se.kth.spork.base3dm.Revision
import se.kth.spork.spoon.wrappers.NodeFactory
import se.kth.spork.spoon.wrappers.SpoonNode
import spoon.reflect.declaration.CtElement
import spoon.reflect.visitor.CtScanner

/**
 * Utility class for removing a node, along with all of its descendants, from a [SpoonMapping] instance.
 *
 * @author Simon Larsén
 */
class MappingRemover(private val mapping: SpoonMapping) : CtScanner() {

    /**
     * Remove this node and its associated virtual nodes from the mapping, and recursively remove all of its
     * descendants in the same way.
     *
     * @param node A node to remove from the mapping.
     */
    private fun removeRelatedMappings(node: SpoonNode) {
        val elem = node.element
        scan(elem)
    }

    override fun scan(element: CtElement?) {
        if (element == null) {
            return
        }
        val node = NodeFactory.wrap(element)
        mapping.remove(node)
        mapping.remove(node.startOfChildList)
        mapping.remove(node.startOfChildList)
        super.scan(element)
    }

    companion object {
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
        fun removeFromMappings(nodes: Set<SpoonNode>, baseLeft: SpoonMapping, baseRight: SpoonMapping, leftRight: SpoonMapping) {
            val baseLeftMappingRemover = MappingRemover(baseLeft)
            val baseRightMappingRemover = MappingRemover(baseRight)
            val leftRightMappingRemover = MappingRemover(leftRight)
            for (node in nodes) {
                when (node.revision) {
                    Revision.BASE -> {
                        leftRightMappingRemover.removeRelatedMappings(baseLeft.getDst(node)!!)
                        baseLeftMappingRemover.removeRelatedMappings(node)
                        baseRightMappingRemover.removeRelatedMappings(node)
                    }
                    Revision.LEFT -> {
                        baseLeftMappingRemover.removeRelatedMappings(node)
                        leftRightMappingRemover.removeRelatedMappings(node)
                    }
                    Revision.RIGHT -> {
                        baseRightMappingRemover.removeRelatedMappings(node)
                        leftRightMappingRemover.removeRelatedMappings(node)
                    }
                }
            }
        }
    }
}
