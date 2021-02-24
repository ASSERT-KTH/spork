package se.kth.spork.spoon

import se.kth.spork.spoon.wrappers.RoledValue
import se.kth.spork.spoon.wrappers.RoledValues
import se.kth.spork.spoon.wrappers.SpoonNode
import spoon.reflect.code.CtBinaryOperator
import spoon.reflect.code.CtComment
import spoon.reflect.code.CtLiteral
import spoon.reflect.code.CtLocalVariable
import spoon.reflect.code.CtOperatorAssignment
import spoon.reflect.code.CtUnaryOperator
import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.CtMethod
import spoon.reflect.declaration.CtModifiable
import spoon.reflect.declaration.CtNamedElement
import spoon.reflect.declaration.CtParameter
import spoon.reflect.path.CtRole
import spoon.reflect.reference.CtReference
import spoon.reflect.reference.CtWildcardReference

/**
 * @param node A [SpoonNode]
 * @return The content of the node.
 */
fun getContent(node: SpoonNode) = getContent(node.element)

/**
 * Return the content of the supplied Spoon element. For example, the content of a CtLiteral is
 * its value, and the content of a CtNamedElement is its simple name.
 *
 * @param elem A node to resolve the content for.
 * @return The content of the node.
 */
private fun getContent(elem: CtElement): RoledValues {
    val rvs = RoledValues(elem)

    // general values
    rvs.add(CtRole.IS_IMPLICIT, elem.isImplicit)

    // element-specific values
    if (elem is CtLiteral<*>) {
        rvs.add(CtRole.VALUE, elem.value)
    } else if (elem is CtReference || elem is CtNamedElement) {
        val name = elem.getValueByRole<String>(CtRole.NAME)
        if (name.matches("\\d+".toRegex())) {
            // If the name is a digit, it's an anonymous class. We resolve that to the 0 to prevent content
            // mismatching on the names of anonymous functions, which don't matter as far as merging goes. This
            // might cause other issues, though, but it's the best idea I've got at this time. It's important
            // that an anonymous class' name is a number as this identifies them as anonymous,
            // see https://github.com/kth/spork/issues/93
            rvs.add(CtRole.NAME, "0")
        } else {
            rvs.add(CtRole.NAME, elem.getValueByRole<Any>(CtRole.NAME))
        }
    } else if (elem is CtBinaryOperator<*> ||
        elem is CtUnaryOperator<*> ||
        elem is CtOperatorAssignment<*, *>
    ) {
        rvs.add(CtRole.OPERATOR_KIND, elem.getValueByRole<Any>(CtRole.OPERATOR_KIND))
    }
    if (elem is CtParameter<*>) {
        rvs.add(CtRole.IS_VARARGS, elem.getValueByRole<Any>(CtRole.IS_VARARGS))
        rvs.add(CtRole.IS_INFERRED, elem.getValueByRole<Any>(CtRole.IS_INFERRED))
    }
    if (elem is CtLocalVariable<*>) {
        rvs.add(CtRole.IS_INFERRED, elem.getValueByRole<Any>(CtRole.IS_INFERRED))
    }
    if (elem is CtModifiable) {
        rvs.add(CtRole.MODIFIER, elem.getValueByRole<Any>(CtRole.MODIFIER))
    }
    if (elem is CtWildcardReference) {
        rvs.add(CtRole.IS_UPPER, elem.getValueByRole<Any>(CtRole.IS_UPPER))
    }
    if (elem is CtComment) {
        val rawContent = elem.rawContent
        val content = RoledValue(
            CtRole.COMMENT_CONTENT, elem.getValueByRole<Any>(CtRole.COMMENT_CONTENT)
        )
        content.putMetadata(RoledValue.Key.RAW_CONTENT, rawContent)
        rvs.add(content)
        rvs.add(CtRole.COMMENT_TYPE, elem.getValueByRole<Any>(CtRole.COMMENT_TYPE))
    }
    if (elem is CtMethod<*>) {
        rvs.add(CtRole.IS_DEFAULT, elem.getValueByRole<Any>(CtRole.IS_DEFAULT))
    }
    return rvs
}
