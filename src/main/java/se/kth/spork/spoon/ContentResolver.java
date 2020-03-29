package se.kth.spork.spoon;

import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtWildcardReference;

import java.util.function.Function;

/**
 * This class determines what the content of any given type of node is.
 *
 * @author Simon Larsén
 */
class ContentResolver implements Function<SpoonNode, RoledValues> {

    /**
     * Return the content of the supplied node. For example, the content of a CtLiteral is its value, and the
     * content of a CtNamedElement is its simple name.
     *
     * @param node A node to resolve the content for.
     * @return The content of the node.
     */
    @Override
    public RoledValues apply(SpoonNode node) {
        return getContent(node.getElement());
    }

    /**
     * Return the content of the supplied Spoon element. For example, the content of a CtLiteral is its value, and the
     * content of a CtNamedElement is its simple name.
     *
     * @param elem A node to resolve the content for.
     * @return The content of the node.
     */
    public static RoledValues getContent(CtElement elem) {
        RoledValues rvs = new RoledValues(elem);

        // general values
        rvs.add(CtRole.IS_IMPLICIT, elem.isImplicit());

        // element-specific values
        if (elem instanceof CtLiteral) {
            CtLiteral<?> lit = (CtLiteral<?>) elem;
            rvs.add(CtRole.VALUE, lit.getValue());
        } else if (elem instanceof CtReference || elem instanceof CtNamedElement) {
            String name = elem.getValueByRole(CtRole.NAME);
            if (name.matches("\\d+")) {
                // If the name is a digit, it's an anonymous class. We resolve that to the empty string to prevent
                // content mismatching on the names of anonymous functions, which don't matter as far as merging goes.
                // This might cause other issues, though, but it's the best idea I've got at this time.
                rvs.add(CtRole.NAME, "");
            } else {
                rvs.add(CtRole.NAME, elem.getValueByRole(CtRole.NAME));
            }
        } else if (elem instanceof CtBinaryOperator || elem instanceof CtUnaryOperator || elem instanceof CtOperatorAssignment) {
            rvs.add(CtRole.OPERATOR_KIND, elem.getValueByRole(CtRole.OPERATOR_KIND));
        }

        if (elem instanceof CtParameter) {
            rvs.add(CtRole.IS_VARARGS, elem.getValueByRole(CtRole.IS_VARARGS));
            rvs.add(CtRole.IS_INFERRED, elem.getValueByRole(CtRole.IS_INFERRED));
        }
        if (elem instanceof CtLocalVariable) {
            rvs.add(CtRole.IS_INFERRED, elem.getValueByRole(CtRole.IS_INFERRED));
        }
        if (elem instanceof CtModifiable) {
            rvs.add(CtRole.MODIFIER, elem.getValueByRole(CtRole.MODIFIER));
        }
        if (elem instanceof CtWildcardReference) {
            rvs.add(CtRole.IS_UPPER, elem.getValueByRole(CtRole.IS_UPPER));
        }
        if (elem instanceof CtComment) {
            String rawContent = ((CtComment) elem).getRawContent();
            RoledValue content = new RoledValue(CtRole.COMMENT_CONTENT, elem.getValueByRole(CtRole.COMMENT_CONTENT));
            content.putMetadata(RoledValue.Key.RAW_CONTENT, rawContent);

            rvs.add(content);
            rvs.add(CtRole.COMMENT_TYPE, elem.getValueByRole(CtRole.COMMENT_TYPE));
        }
        if (elem instanceof CtMethod) {
            rvs.add(CtRole.IS_DEFAULT, elem.getValueByRole(CtRole.IS_DEFAULT));
        }

        return rvs;
    }
}
