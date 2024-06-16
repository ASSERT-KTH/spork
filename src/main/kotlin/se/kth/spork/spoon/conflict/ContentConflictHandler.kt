package se.kth.spork.spoon.conflict

import spoon.reflect.declaration.CtElement
import spoon.reflect.path.CtRole

/**
 * Interface that defines a content conflict handler.
 *
 * @author Simon Larsén
 */
interface ContentConflictHandler {
    /**
     * Handle a content conflict.
     *
     *
     * The boolean in the return value should be true if the content was partially merged, and
     * the optional value must then be non-empty.
     *
     *
     * If the content was not merged at all, then the boolean should be false, and the value
     * should be an empty optional.
     *
     *
     * If the content was fully merged, then the boolean should be false and the value should be
     * a non-empty optional.
     *
     * @param baseVal The value from the base revision. Not always present.
     * @param leftVal The value from the left revision.
     * @param rightVal The value from the right revision.
     * @param baseElem The base element, from which the base value was taken. Not always present.
     * @param leftElem The left element, from which the left value was taken.
     * @param rightElem The right element, from which the right value was taken.
     * @return A pair (mergedContent, isPartiallyMerged).
     */
    fun handleConflict(
        baseVal: Any?,
        leftVal: Any,
        rightVal: Any,
        baseElem: CtElement?,
        leftElem: CtElement,
        rightElem: CtElement,
        diff3: Boolean,
    ): Pair<Any?, Boolean>

    /** @return The role that this conflict handler deals with.
     */
    val role: CtRole
}
