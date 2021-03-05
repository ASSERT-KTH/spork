package se.kth.spork.spoon.conflict

import se.kth.spork.base3dm.Content
import spoon.reflect.path.CtRole
import se.kth.spork.spoon.wrappers.SpoonNode
import se.kth.spork.spoon.wrappers.RoledValues
import java.util.Deque
import se.kth.spork.base3dm.Revision
import java.util.HashMap
import se.kth.spork.util.Pair
import se.kth.spork.util.Triple
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Optional

/**
 * A class for dealing with merging of content.
 *
 * @author Simon Larsén
 */
class ContentMerger(conflictHandlers: List<ContentConflictHandler>) {
    private val conflictHandlers: MutableMap<CtRole?, ContentConflictHandler>

    /**
     * @param nodeContents The contents associated with this node.
     * @return A pair of merged contents and a potentially empty collection of unresolved conflicts.
     */
    fun mergedContent(
        nodeContents: Set<Content<SpoonNode, RoledValues>>
    ): Pair<RoledValues?, List<ContentConflict>> {
        if (nodeContents.size == 1) {
            return Pair.of(nodeContents.iterator().next().value, emptyList())
        }
        val revisions = getContentRevisions(nodeContents)
        val baseRoledValues = revisions.first?.value
        val leftRoledValues = revisions.second.value
        val rightRoledValues = revisions.third.value

        // NOTE: It is important that the left values are copied,
        // by convention the LEFT values should be put into the tree whenever a conflict cannot be
        // resolved
        val mergedRoledValues = RoledValues(leftRoledValues)
        assert(leftRoledValues.size == rightRoledValues.size)
        val unresolvedConflicts: Deque<ContentConflict> = ArrayDeque()
        for (i in leftRoledValues.indices) {
            val leftRv = leftRoledValues[i]
            val rightRv = rightRoledValues[i]
            assert(leftRv.role == rightRv.role)
            val role = leftRv.role

            val baseVal: Any? = baseRoledValues?.get(i)?.value
            val leftVal: Any = leftRv.value!!
            val rightVal: Any = rightRv.value!!
            if (leftRv == rightRv) {
                // this pair cannot possibly conflict
                continue
            }

            // left and right pairs differ and are so conflicting
            // we add them as a conflict, but will later remove it if the conflict can be resolved
            unresolvedConflicts.push(
                ContentConflict(
                    role,
                    baseRoledValues?.get(i),
                    leftRv,
                    rightRv
                )
            )
            var merged: Any? = null

            // sometimes a value can be partially merged (e.g. modifiers), and then we want to be
            // able to set the merged value, AND flag a conflict.
            var conflictPresent = false

            // if either value is equal to base, we keep THE OTHER one
            if (baseVal == leftVal) {
                merged = rightVal
            } else if (baseVal == rightVal) {
                merged = leftVal
            } else {
                // non-trivial conflict, check if there is a conflict handler for this role
                val handler = conflictHandlers[role]
                if (handler != null) {
                    val result = handler.handleConflict(
                        baseVal,
                        leftVal,
                        rightVal,
                        baseRoledValues?.element,
                        leftRoledValues.element,
                        rightRoledValues.element
                    )
                    merged = result!!.first!!
                    conflictPresent = result.second!!
                }
            }
            if (merged != null) {
                mergedRoledValues[i, role] = merged
                if (!conflictPresent) unresolvedConflicts.pop()
            }
        }
        return Pair.of(mergedRoledValues, ArrayList(unresolvedConflicts))
    }

    // this is just a type alias to declutter the getContentRevisions method header
    private class _ContentTriple(
        first: Content<SpoonNode, RoledValues>?,
        second: Content<SpoonNode, RoledValues>,
        third: Content<SpoonNode, RoledValues>
    ) : Triple<Content<SpoonNode, RoledValues>?, Content<SpoonNode, RoledValues>, Content<SpoonNode, RoledValues>>(
        first,
        second,
        third
    )

    companion object {
        private fun getContentRevisions(
            contents: Set<Content<SpoonNode, RoledValues>>
        ): _ContentTriple {
            var base: Content<SpoonNode, RoledValues>? = null
            var left: Content<SpoonNode, RoledValues>? = null
            var right: Content<SpoonNode, RoledValues>? = null
            for (cnt in contents) {
                when (cnt.revision) {
                    Revision.BASE -> base = cnt
                    Revision.LEFT -> left = cnt
                    Revision.RIGHT -> right = cnt
                }
            }
            require(!(left == null || right == null)) { "Expected at least left and right revisions, got: $contents" }
            return _ContentTriple(base, left, right)
        }
    }

    /**
     * @param conflictHandlers A list of conflict handlers. There may only be one handler per role.
     */
    init {
        this.conflictHandlers = HashMap()
        for (handler in conflictHandlers) {
            require(!this.conflictHandlers.containsKey(handler.role)) { "duplicate handler for role " + handler.role }
            this.conflictHandlers[handler.role] = handler
        }
    }
}