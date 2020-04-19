package se.kth.spork.spoon.pcsinterpreter;

import se.kth.spork.base3dm.ChangeSet;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.matching.SpoonMapping;
import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.Pair;
import spoon.compiler.Environment;
import spoon.reflect.declaration.CtElement;

/**
 * Class for interpreting a merged PCS structure into a Spoon tree.
 *
 * @author Simon Larsén
 */
public class PcsInterpreter {
    /**
     * Convert a merged PCS structure into a Spoon tree.
     *
     * @param baseLeft  A tree matching between the base revision and the left revision.
     * @param baseRight A tree matching between the base revision and the right revision.
     * @return A pair on the form (tree, hasStructuralConflicts).
     */
    public static Pair<CtElement, Boolean> fromMergedPcs(
            ChangeSet<SpoonNode, RoledValues> delta,
            SpoonMapping baseLeft,
            SpoonMapping baseRight) {
        SporkTreeBuilder sporkTreeBuilder = new SporkTreeBuilder(delta);
        SporkTree sporkTreeRoot = sporkTreeBuilder.build(NodeFactory.ROOT);

        // this is a bit of a hack, get any used environment such that the SpoonTreeBuilder can copy environment
        // details
        Environment oldEnv = sporkTreeRoot.getChildren().get(0).getNode().getElement().getFactory().getEnvironment();

        SpoonTreeBuilder spoonTreeBuilder = new SpoonTreeBuilder(baseLeft, baseRight, oldEnv);
        CtElement spoonTreeRoot = spoonTreeBuilder.build(sporkTreeRoot);

        return Pair.of(spoonTreeRoot, sporkTreeBuilder.hasStructuralConflict() || spoonTreeBuilder.hasContentConflict());
    }
}
