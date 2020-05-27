package se.kth.spork.spoon.conflict;

import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.LazyLogger;
import spoon.reflect.path.CtRole;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A conflict handler that can resolve method ordering conflicts.
 *
 * @author Simon Larsén
 */
public class MethodOrderingConflictHandler implements StructuralConflictHandler {
    private static final LazyLogger LOGGER = new LazyLogger(MethodOrderingConflictHandler.class);

    @Override
    public Optional<List<SpoonNode>> tryResolveConflict(List<SpoonNode> leftNodes, List<SpoonNode> rightNodes, ConflictType type) {
        // we currently don't care about the type but it could be relevant in the future
        if (type != ConflictType.INSERT_INSERT) {
            LOGGER.warn(() -> getClass().getSimpleName()
                    + " not designed to handle ordering conflicts for conflict type "
                    + ConflictType.INSERT_INSERT + ", but it may be possible");
            return Optional.empty();
        }

        SpoonNode firstNode = leftNodes.size() > 0 ? leftNodes.get(0) : rightNodes.get(0);
        if (!(firstNode.getElement().getRoleInParent() == CtRole.TYPE_MEMBER))
            return Optional.empty();

        assert leftNodes.stream().allMatch(node -> node.getElement().getRoleInParent() == CtRole.TYPE_MEMBER);
        assert rightNodes.stream().allMatch(node -> node.getElement().getRoleInParent() == CtRole.TYPE_MEMBER);

        // FIXME this is too liberal. Fields are not unordered, and this approach makes the merge non-commutative.
        List<SpoonNode> result = Stream.of(leftNodes, rightNodes).flatMap(List::stream).collect(Collectors.toList());
        return Optional.of(result);
    }
}
