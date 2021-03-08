package se.kth.spork.spoon.conflict

import spoon.reflect.path.CtRole
import se.kth.spork.spoon.wrappers.SpoonNode
import se.kth.spork.util.LazyLogger

/**
 * A conflict handler that can resolve method ordering conflicts.
 *
 * @author Simon Larsén
 */
class MethodOrderingConflictHandler : StructuralConflictHandler {
    override fun tryResolveConflict(
        leftNodes: List<SpoonNode>, rightNodes: List<SpoonNode>, type: ConflictType
    ): List<SpoonNode>? {
        // we currently don't care about the type but it could be relevant in the future
        if (type != ConflictType.INSERT_INSERT) {
            LOGGER.warn {
                (javaClass.simpleName
                        + " not designed to handle ordering conflicts for conflict type "
                        + ConflictType.INSERT_INSERT
                        + ", but it may be possible")
            }
            return null
        }
        val firstNode = if (leftNodes.isNotEmpty()) leftNodes[0] else rightNodes[0]
        return if (firstNode.element.roleInParent != CtRole.TYPE_MEMBER) {
            null
        } else {
            assert(leftNodes.all { node: SpoonNode -> node.element.roleInParent == CtRole.TYPE_MEMBER })
            assert(rightNodes.all { node: SpoonNode -> node.element.roleInParent == CtRole.TYPE_MEMBER })

            // FIXME this is too liberal. Fields are not unordered, and this approach makes the merge
            // non-commutative.
            leftNodes + rightNodes
        }
    }

    companion object {
        private val LOGGER = LazyLogger(MethodOrderingConflictHandler::class.java)
    }
}