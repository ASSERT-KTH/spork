package se.kth.spork.spoon.conflict

import se.kth.spork.spoon.wrappers.SpoonNode

/**
 * A structural conflict handler that optimistically resolves insert/insert conflicts in which one
 * side is empty by choosing the non-empty side.
 *
 * @author Simon Larsén
 */
class OptimisticInsertInsertHandler : StructuralConflictHandler {
    override fun tryResolveConflict(
        leftNodes: List<SpoonNode>,
        rightNodes: List<SpoonNode>,
        type: ConflictType,
    ): List<SpoonNode>? {
        return if (leftNodes.isNotEmpty() && rightNodes.isNotEmpty() || type != ConflictType.INSERT_INSERT) {
            null
        } else if (leftNodes.isNotEmpty()) {
            leftNodes
        } else {
            rightNodes
        }
    }
}
