package se.kth.spork.spoon.conflict

import se.kth.spork.spoon.wrappers.SpoonNode

/**
 * A an interface for structural conflict handlers that can automatically resolve a particular type
 * of conflict.
 *
 * @author Simon Larsén
 */
interface StructuralConflictHandler {
    /**
     * Attempt to resolve a structural conflict. If the conflict is resolved, a non-empty optional
     * is returned with the nodes in the order that theys should appear. The list in the optional
     * may however be empty.
     *
     * @param leftNodes Nodes from the left side of the conflict.
     * @param rightNodes Nodes from the right side of the conflict.
     * @return An optional list of nodes, where a present value indicates that the conflict was
     * successfully resolved.
     */
    fun tryResolveConflict(
        leftNodes: List<SpoonNode>,
        rightNodes: List<SpoonNode>,
        type: ConflictType
    ): List<SpoonNode>?
}
