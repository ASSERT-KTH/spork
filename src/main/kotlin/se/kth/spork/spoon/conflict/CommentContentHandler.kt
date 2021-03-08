package se.kth.spork.spoon.conflict

import spoon.reflect.path.CtRole
import spoon.reflect.declaration.CtElement
import se.kth.spork.util.LineBasedMerge
import se.kth.spork.util.Pair

/**
 * A conflict handler for comment contents.
 *
 * @author Simon Larsén
 */
class CommentContentHandler : ContentConflictHandler {
    override val role: CtRole
        get() = CtRole.COMMENT_CONTENT

    override fun handleConflict(
        baseVal: Any?,
        leftVal: Any,
        rightVal: Any,
        baseElem: CtElement?,
        leftElem: CtElement,
        rightElem: CtElement
    ): Pair<Any?, Boolean> {
        return Pair.of(mergeComments(baseVal ?: "", leftVal, rightVal), false)
    }

    private fun mergeComments(base: Any, left: Any, right: Any): Any? {
        val merge = LineBasedMerge.merge(base.toString(), left.toString(), right.toString())
        return if (merge.second > 0) null
        else merge.first
    }
}