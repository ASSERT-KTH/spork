package se.kth.spork.spoon.conflict

import spoon.reflect.path.CtRole
import spoon.reflect.declaration.CtElement
import spoon.reflect.reference.CtWildcardReference
import se.kth.spork.util.Pair
import java.util.Optional

/**
 * A conflict handler for the IS_UPPER attribute. This appears on wildcards to specify if a type
 * bound is an upper or a lower type bound. For example `` means that
 * IS_UPPER is true, and `` means that IS_UPPER is false.
 *
 * @author Simon Larsén
 */
class IsUpperHandler : ContentConflictHandler {
    override val role: CtRole
        get() = CtRole.IS_UPPER

    override fun handleConflict(
        baseVal: Any?,
        leftVal: Any,
        rightVal: Any,
        baseElem: CtElement?,
        leftElem: CtElement,
        rightElem: CtElement
    ): Pair<Any?, Boolean> {
        return Pair.of(mergeIsUpper(baseElem, leftElem, rightElem), false)
    }

    companion object {
        private fun mergeIsUpper(
            baseElem: CtElement?, leftElem: CtElement, rightElem: CtElement
        ): Optional<Any> {
            val left = leftElem as CtWildcardReference
            val right = rightElem as CtWildcardReference
            val leftBoundIsImplicit = left.boundingType.isImplicit
            val rightBoundIsImplicit = right.boundingType.isImplicit
            if (baseElem != null) {
                val base = baseElem as CtWildcardReference
                val baseBoundIsImplicit = base.boundingType.isImplicit
                if (leftBoundIsImplicit != rightBoundIsImplicit) {
                    // one bound was removed, so we go with whatever is on the bound that is not equal
                    // to base
                    return Optional.of(
                        if (baseBoundIsImplicit == leftBoundIsImplicit) left.isUpper else right.isUpper
                    )
                }
            } else {
                if (leftBoundIsImplicit != rightBoundIsImplicit) {
                    // only one bound implicit, pick isUpper of the explicit one
                    return Optional.of(if (leftBoundIsImplicit) left.isUpper else right.isUpper)
                }
            }
            return Optional.empty()
        }
    }
}