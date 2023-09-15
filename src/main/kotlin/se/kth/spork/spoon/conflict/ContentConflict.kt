package se.kth.spork.spoon.conflict

import se.kth.spork.spoon.wrappers.RoledValue
import spoon.reflect.path.CtRole

class ContentConflict(
    val role: CtRole,
    val base: RoledValue?,
    val left: RoledValue,
    val right: RoledValue,
) {

    companion object {
        const val METADATA_KEY = "SPORK_CONTENT_CONFLICT"
    }
}
