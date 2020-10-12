package se.kth.spork.spoon.wrappers

import spoon.reflect.declaration.CtElement
import spoon.reflect.path.CtRole
import java.util.*

/**
 * Class representing some form of value in a Spoon node, along with the role the value has. This is for
 * example the name of a method, or the value in a literal.
 *
 * @author Simon Larsén
 */
class RoledValues : ArrayList<RoledValue?> {
    val element: CtElement

    constructor(element: CtElement) : super() {
        this.element = element
    }

    constructor(other: RoledValues) : super(other) {
        element = other.element.clone()
    }

    fun add(role: CtRole?, value: Any?) {
        add(RoledValue(role!!, value!!))
    }

    operator fun set(i: Int, role: CtRole?, value: Any?): RoledValue? {
        return set(i, RoledValue(role!!, value!!))
    }

}