package se.kth.spork.spoon.wrappers

import spoon.reflect.path.CtRole
import java.util.EnumMap
import java.util.Objects

class RoledValue(val role: CtRole, val value: Any?) {
    private val metadata: MutableMap<Key, Any> = EnumMap(se.kth.spork.spoon.wrappers.RoledValue.Key::class.java)

    enum class Key {
        RAW_CONTENT
    }

    fun putMetadata(key: Key, value: Any) {
        metadata[key] = value
    }

    fun getMetadata(key: Key?): Any? {
        return metadata[key]
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as RoledValue
        return role == that.role &&
            value == that.value
    }

    override fun hashCode(): Int {
        return Objects.hash(role, value)
    }
}
