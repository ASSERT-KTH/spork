package se.kth.spork.base3dm

import se.kth.spork.exception.ConflictException
import se.kth.spork.util.LazyLogger

/**
 * An implementation of the 3DM merge algorithm by Tancred Lindholm. For details on 3DM merge, see the paper
 * <a href="https://doi.org/10.1145/1030397.1030399">A three-way merge for XML documents</a>.
 *
 * @author Simon Larsén
 */

const val REV: String = "rev"

private val LOGGER: LazyLogger = LazyLogger(Object::class.java)

/**
 * Attempt to resolve a raw merge by incrementally removing inconsistencies. The input delta is the raw merge, which
 * typically is an inconsistent PCS tree. When the algorithm has finished running, delta should be a forest of
 * consistent PCS trees. The forest consists of the merged tree, as well as any deleted subtrees.
 *
 * A structural or content conflict means that 3DM was unable to create a consistent PCS structure.
 *
 * @param base The base revision.
 * @param delta The raw merge.
 */
fun <T : ListNode, V> resolveRawMerge(base: ChangeSet<T, V>, delta: ChangeSet<T, V>) {
    for (pcs in delta.pcsSet) {
        if (!delta.contains(pcs)) {
            // was removed as otherPcs
            continue
        }

        // We need to merge the content of the predecessor and successor, but we can skip the parent.
        // The reason is that a parent node that never appears as a predecessor or successor will never be
        // processed when converting from PCS to tree, with the exception of the virtual root (which has no content).
        // It is however possible for a node to only appear as predecessor or successor in certain conflict
        // situations, see https://github.com/kth/spork/issues/82 for details
        mergeContent(pcs.predecessor, base, delta)
        mergeContent(pcs.successor, base, delta)

        val others: List<Pcs<T>> = delta.getOtherRoots(pcs) +
            delta.getOtherPredecessors(pcs) +
            delta.getOtherSuccessors(pcs)
        others.forEach {
            when {
                base.contains(it) -> delta.remove(it)
                base.contains(pcs) -> delta.remove(pcs)
                else -> delta.registerStructuralConflict(pcs, it)
            }
        }
    }
    val structuralConflicts: Map<Pcs<T>, Set<Pcs<T>>> = delta.structuralConflicts
    if (structuralConflicts.isNotEmpty()) {
        LOGGER.warn { "STRUCTURAL CONFLICTS DETECTED: $structuralConflicts" }
    }
}

/**
 * Merge the content of a node, if possible.
 */
private fun <T : ListNode, V> mergeContent(node: T, base: ChangeSet<T, V>, delta: ChangeSet<T, V>) {
    val contents: Set<Content<T, V>> = delta.getContent(node)
    if (contents.size > 1) {
        val newContent = handleContentConflict(contents, base)
        delta.setContent(node, newContent.toMutableSet())
    }
}

/**
 * Handle content conflicts, i.e. the same node is associated with multiple (potentially equivalent) contents.
 *
 * If the conflict can be automatically resolved, the new contents (with only one piece of content) are returned.
 *
 * If the content conflict cannot be automatically resolved, the contents argument is simply returned as-is.
 */
private fun <T : ListNode, V> handleContentConflict(contents: Set<Content<T, V>>, base: ChangeSet<T, V>): Set<Content<T, V>> {
    if (contents.size > 3) throw IllegalArgumentException("expected at most 3 pieces of conflicting content, got: $contents")
    val basePcs = contents.find { base.contains(it) }
    val newContent = contents.filterNot { basePcs?.value == it.value }.distinctBy { it.value }.toSet()

    return when {
        newContent.size > 2 -> {
            // This should never happen, as there are at most 3 pieces of content to begin with and base has been removed.
            throw ConflictException("Unexpected amount of conflicting content: $newContent")
        }
        newContent.isEmpty() -> {
            // can only happen if all content was equal to the base revision
            setOf(basePcs!!)
        }
        newContent.size != 1 -> {
            // conflict could not be resolved
            val it: Iterator<Content<T, V>> = newContent.iterator()
            val first: Content<T, V> = it.next()
            val second: Content<T, V> = it.next()
            LOGGER.warn { "Content conflict: $first, $second" }

            // the reason all content is returned is that further processing of conflicts may be done after the base
            // 3DM merge has finished, which may require the content of all revisions
            contents
        }
        else -> newContent
    }
}
