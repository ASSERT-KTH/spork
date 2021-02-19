package se.kth.spork.spoon.conflict;

import java.util.Optional;
import se.kth.spork.util.LineBasedMerge;
import se.kth.spork.util.Pair;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

/**
 * A conflict handler for comment contents.
 *
 * @author Simon Larsén
 */
public class CommentContentHandler implements ContentConflictHandler {
    @Override
    public CtRole getRole() {
        return CtRole.COMMENT_CONTENT;
    }

    @Override
    public Pair<Optional<Object>, Boolean> handleConflict(
            Optional<Object> baseVal,
            Object leftVal,
            Object rightVal,
            Optional<CtElement> baseElem,
            CtElement leftElem,
            CtElement rightElem) {
        return Pair.of(mergeComments(baseVal.orElse(""), leftVal, rightVal), false);
    }

    private static Optional<Object> mergeComments(Object base, Object left, Object right) {
        Pair<String, Integer> merge =
                LineBasedMerge.merge(base.toString(), left.toString(), right.toString());
        if (merge.second > 0) {
            return Optional.empty();
        }
        return Optional.of(merge.first);
    }
}
