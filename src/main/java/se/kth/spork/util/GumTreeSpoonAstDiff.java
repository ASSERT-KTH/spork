package se.kth.spork.util;

import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtStatementList;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtReference;

/**
 * This file is composed of methods copied from the gumtree-spoon-ast-diff project, and is therefore
 * licensed under the Apache License 2.0, Copyright Mattias Martinez.
 *
 * <p><a
 * href="https://github.com/SpoonLabs/gumtree-spoon-ast-diff/blob/dae908192bee7773b38d149baff831ee616ec524/LICENSE.txt#L1">
 * See the license statement here. </a>
 *
 * <p>Each method is individually annotate to state where it was taken from.
 */
public class GumTreeSpoonAstDiff {
    /**
     * This method is taken from gumtree-spoon-ast-diff and identifies Spoon nodes that should not
     * be mapped into the GumTree that's build by SpoonGumTreeBuilder.
     *
     * @author Mattias Martinez
     *     <p>See <a
     *     href="https://github.com/SpoonLabs/gumtree-spoon-ast-diff/blob/dae908192bee7773b38d149baff831ee616ec524/src/main/java/gumtree/spoon/builder/TreeScanner.java#L71-L84">TreeScanner</a>
     *     for the original source.
     *     <p>TODO Don't duplicate this code...
     * @param element An element to check if it is to be ignored.
     * @return Whether or not to ignore the argument.
     */
    public static boolean isToIgnore(CtElement element) {
        if (element instanceof CtStatementList && !(element instanceof CtCase)) {
            return element.getRoleInParent() != CtRole.ELSE
                    && element.getRoleInParent() != CtRole.THEN;
        }

        if (element instanceof CtReference && element.getRoleInParent() == CtRole.SUPER_TYPE) {
            return false;
        }

        return element.isImplicit() || element instanceof CtReference;
    }
}
