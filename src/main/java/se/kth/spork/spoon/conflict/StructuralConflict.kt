package se.kth.spork.spoon.conflict

import se.kth.spork.spoon.wrappers.SpoonNode
import spoon.reflect.declaration.CtElement
import java.util.HashSet
import se.kth.spork.base3dm.Pcs

/**
 * A simple class that provides some information on a structural conflict. Meant to be put as
 * metadata on a conflict "dummy node" using [StructuralConflict.METADATA_KEY] as the key.
 *
 * @author Simon Larsén
 */
class StructuralConflict {
    val left: List<CtElement>
    val right: List<CtElement>
    val base: List<CtElement>?
    val lineBasedMerge: String?

    /**
     * @param left The left part of the conflict.
     * @param right The right part of the conflict.
     */
    constructor(left: List<CtElement>, right: List<CtElement>) {
        this.left = left
        this.right = right
        base = null
        lineBasedMerge = null
    }

    /** Create an approximated conflict.  */
    constructor(
        base: CtElement?, left: CtElement, right: CtElement, lineBasedMerge: String
    ) {
        this.base = if (base != null) listOf(base) else null
        this.left = listOf(left)
        this.right = listOf(right)
        this.lineBasedMerge = lineBasedMerge
    }

    companion object {
        const val METADATA_KEY = "SPORK_STRUCTURAL_CONFLICT"
        fun isRootConflict(left: Pcs<*>, right: Pcs<*>): Boolean {
            return (left.root != right.root
                    && (left.predecessor == right.predecessor
                    || left.successor == right.successor))
        }

        fun isPredecessorConflict(left: Pcs<*>, right: Pcs<*>): Boolean {
            return (left.predecessor != right.predecessor
                    && left.successor == right.successor
                    && left.root == right.root)
        }

        fun isSuccessorConflict(left: Pcs<*>, right: Pcs<*>): Boolean {
            return (left.successor != right.successor
                    && left.predecessor == right.predecessor
                    && left.root == right.root)
        }

        fun extractRootConflictingNodes(
            structuralConflicts: Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>>
        ): Set<SpoonNode> {
            val toIgnore: MutableSet<SpoonNode> = HashSet()
            for ((pcs, value) in structuralConflicts) {
                for (other in value) {
                    if (isRootConflict(pcs, other)) {
                        if (pcs.predecessor == other.predecessor) {
                            toIgnore.add(other.predecessor)
                        }
                        if (pcs.successor == other.successor) {
                            toIgnore.add(other.successor)
                        }
                    }
                }
            }
            return toIgnore
        }
    }
}