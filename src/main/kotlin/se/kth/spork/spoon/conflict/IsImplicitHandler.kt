package se.kth.spork.spoon.conflict

import spoon.reflect.declaration.CtElement
import spoon.reflect.path.CtRole
import kotlin.Pair

/**
 * Conflict handler for the IS_IMPLICIT attribute.
 *
 * @author Simon Larsén
 */
class IsImplicitHandler : ContentConflictHandler {
    override val role: CtRole
        get() = CtRole.IS_IMPLICIT

    override fun handleConflict(
        baseVal: Any?,
        leftVal: Any,
        rightVal: Any,
        baseElem: CtElement?,
        leftElem: CtElement,
        rightElem: CtElement,
        diff3: Boolean,
    ): Pair<Any?, Boolean> {
        val mergedValue = if (baseVal != null) {
            // as there are only two possible values for a boolean, left and right disagreeing must
            // mean that the base value has been changed
            !(baseVal as Boolean)
        } else {
            // left and right disagree and base is unavailable; discarding implicitness most often
            // works
            false
        }

        return Pair(mergedValue, false)
    }
}
