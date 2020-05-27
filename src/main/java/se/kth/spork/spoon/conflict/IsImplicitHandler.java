package se.kth.spork.spoon.conflict;

import se.kth.spork.util.Pair;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

import java.util.Optional;

/**
 * Conflict handler for the IS_IMPLICIT attribute.
 *
 * @author Simon Larsén
 */
public class IsImplicitHandler implements ContentConflictHandler {
    @Override
    public CtRole getRole() {
        return CtRole.IS_IMPLICIT;
    }

    @Override
    public Pair<Optional<Object>, Boolean> handleConflict(
            Optional<Object> baseVal,
            Object leftVal,
            Object rightVal,
            Optional<CtElement> baseElem,
            CtElement leftElem,
            CtElement rightElem) {
        if (baseVal.isPresent()) {
            // as there are only two possible values for a boolean, left and right disagreeing must mean that the base
            // value has been changed
            Boolean change = !(Boolean) baseVal.get();
            return Pair.of(Optional.of(change), false);
        } else {
            // left and right disagree and base is unavailable; discarding implicitness most often works
            return Pair.of(Optional.of(false), false);
        }
    }
}
