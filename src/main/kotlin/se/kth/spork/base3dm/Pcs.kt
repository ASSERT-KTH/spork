package se.kth.spork.base3dm

import java.util.*

/**
 * Representation of a Parent/Child/Successor triple for 3DM merge. Note that only root, predecessor and successor
 * values affect hashing and equality.
 *
 * @param root The root of this PCS.
 * @param predecessor The predecessor (or child) of this PCS.
 * @param successor The successor of this PCS.
 * @param revision The revision this PCS is related to.
 */
data class Pcs<T : ListNode?>(val root: T, val predecessor: T, val successor: T, val revision: Revision) {

    override fun hashCode(): Int {
        return Objects.hash(root, predecessor, successor)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val pcs = other as Pcs<*>
        return (root == pcs.root
                && predecessor == pcs.predecessor
                && successor == pcs.successor)
    }
}