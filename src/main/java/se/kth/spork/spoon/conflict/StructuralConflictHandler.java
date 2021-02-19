package se.kth.spork.spoon.conflict;

import java.util.List;
import java.util.Optional;
import se.kth.spork.spoon.wrappers.SpoonNode;

/**
 * A an interface for structural conflict handlers that can automatically resolve a particular type
 * of conflict.
 *
 * @author Simon Larsén
 */
public interface StructuralConflictHandler {
    /**
     * Attempt to resolve a structural conflict. If the conflict is resolved, a non-empty optional
     * is returned with the nodes in the order that theys should appear. The list in the optional
     * may however be empty.
     *
     * @param leftNodes Nodes from the left side of the conflict.
     * @param rightNodes Nodes from the right side of the conflict.
     * @return An optional list of nodes, where a present value indicates that the conflict was
     *     successfully resolved.
     */
    Optional<List<SpoonNode>> tryResolveConflict(
            List<SpoonNode> leftNodes, List<SpoonNode> rightNodes, ConflictType type);
}
