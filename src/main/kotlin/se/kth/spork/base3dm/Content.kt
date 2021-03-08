package se.kth.spork.base3dm

import java.util.Objects

/**
 * Container for a tree node's content. The content value belongs to the predecessor of the context PCS.
 *
 * @param context The context of this content. The value is associated with the predecessor of the context.
 * @param value The value of the this content.
 */
data class Content<T : ListNode, V>(val context: Pcs<T>, val value: V, val revision: Revision) {
    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val content = o as Content<*, *>
        return context == content.context &&
            value == content.value
    }

    override fun hashCode(): Int {
        return Objects.hash(context, value)
    }
}
