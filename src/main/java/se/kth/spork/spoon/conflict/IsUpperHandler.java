package se.kth.spork.spoon.conflict;

import se.kth.spork.util.Pair;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtWildcardReference;

import java.util.Optional;

/**
 * A conflict handler for the IS_UPPER attribute. This appears on wildcards to specify if a type bound is an upper
 * or a lower type bound. For example <code><? extends String></code> means that IS_UPPER is true,
 * and <code><? super String></code> means that IS_UPPER is false.
 *
 * @author Simon Larsén
 */
public class IsUpperHandler implements ContentConflictHandler {

    @Override
    public CtRole getRole() {
        return CtRole.IS_UPPER;
    }

    @Override
    public Pair<Optional<Object>, Boolean> handleConflict(
            Optional<Object> baseVal,
            Object leftVal,
            Object rightVal,
            Optional<CtElement> baseElem,
            CtElement leftElem,
            CtElement rightElem) {
        return Pair.of(mergeIsUpper(baseElem, leftElem, rightElem), false);
    }

    private static Optional<Object> mergeIsUpper(Optional<CtElement> baseElem, CtElement leftElem, CtElement rightElem) {
        CtWildcardReference left = (CtWildcardReference) leftElem;
        CtWildcardReference right = (CtWildcardReference) rightElem;

        boolean leftBoundIsImplicit = left.getBoundingType().isImplicit();
        boolean rightBoundIsImplicit = right.getBoundingType().isImplicit();

        if (baseElem.isPresent()) {
            CtWildcardReference base = (CtWildcardReference) baseElem.get();
            boolean baseBoundIsImplicit = base.getBoundingType().isImplicit();

            if (leftBoundIsImplicit != rightBoundIsImplicit) {
                // one bound was removed, so we go with whatever is on the bound that is not equal to base
                return Optional.of(baseBoundIsImplicit == leftBoundIsImplicit ? left.isUpper() : right.isUpper());
            }
        } else {
            if (leftBoundIsImplicit != rightBoundIsImplicit) {
                // only one bound implicit, pick isUpper of the explicit one
                return Optional.of(leftBoundIsImplicit ? left.isUpper() : right.isUpper());
            }
        }

        return Optional.empty();
    }

}
