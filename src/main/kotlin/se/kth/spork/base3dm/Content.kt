package se.kth.spork.base3dm

import java.util.Objects

/**
 * Container for a tree node's content. The content value belongs to the predecessor of the context PCS.
 *
 * @param context The context of this content. The value is associated with the predecessor of the context.
 * @param value The value of the this content.
 */
data class Content<T : ListNode, V>(val context: Pcs<T>, val value: V, val revision: Revision) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val content = other as Content<*, *>
        return context == content.context &&
            value == content.value
    }

    override fun hashCode(): Int {
        return Objects.hash(context, value)
    }
}
