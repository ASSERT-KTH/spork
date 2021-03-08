package se.kth.spork.spoon.conflict

import spoon.reflect.path.CtRole
import se.kth.spork.spoon.wrappers.RoledValue

class ContentConflict(
    val role: CtRole, val base: RoledValue?, val left: RoledValue, val right: RoledValue
) {

    companion object {
        const val METADATA_KEY = "SPORK_CONTENT_CONFLICT"
    }
}