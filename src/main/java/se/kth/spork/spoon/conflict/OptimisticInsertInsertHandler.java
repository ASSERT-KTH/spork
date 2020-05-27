package se.kth.spork.spoon.conflict;

import se.kth.spork.spoon.wrappers.SpoonNode;

import java.util.List;
import java.util.Optional;

/**
 * A structural conflict handler that optimistically resolves insert/insert conflicts in which one side is empty by
 * choosing the non-empty side.
 *
 * @author Simon Larsén
 */
public class OptimisticInsertInsertHandler implements StructuralConflictHandler {
    @Override
    public Optional<List<SpoonNode>> tryResolveConflict(List<SpoonNode> leftNodes, List<SpoonNode> rightNodes, ConflictType type) {
        if (!(leftNodes.isEmpty() || rightNodes.isEmpty()) || type != ConflictType.INSERT_INSERT)
            return Optional.empty();
        return Optional.of(leftNodes.isEmpty() ? rightNodes : leftNodes);
    }
}
