package se.kth.spork.merge.gumtree;

import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.merge.*;
import se.kth.spork.merge.spoon.SpoonMapping;
import spoon.reflect.declaration.CtClass;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of 3DM merge for GumTree.
 *
 * @author Simon Larsén
 */
public class GumTreeMerge {
    public static ITree mergeToTree(CtClass<?> base, CtClass<?> left, CtClass<?> right) {
        TStar<ITree> merge = merge(base, left, right);
        return GumTreeBuilder.pcsToGumTree(merge.getStar(), merge.getContents());
    }

    public static TStar<ITree> merge(CtClass<?> base, CtClass<?> left, CtClass<?> right) {
        ITree baseTree = toGumTree(base);
        ITree leftTree = toGumTree(left);
        ITree rightTree = toGumTree(right);

        Matcher baseLeftMatch = matchTrees(baseTree, leftTree);
        Matcher baseRightMatch = matchTrees(baseTree, rightTree);
        Matcher leftRightMatch = matchTrees(leftTree, rightTree);

        return merge(baseTree, leftTree, rightTree, baseLeftMatch, baseRightMatch, leftRightMatch);
    }

    private static Matcher matchTrees(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher(src, dst);
        matcher.match();
        return matcher;
    }

    private static ITree toGumTree(CtClass<?> cls) {
        return new SpoonGumTreeBuilder().getTree(cls);
    }

    public static TStar<ITree> merge(ITree base, ITree left, ITree right, Matcher matchLeft, Matcher matchRight, Matcher leftRight) {
        Set<Pcs<ITree>> t0 = GumTreePcsBuilder.fromGumTree(base, Revision.BASE);
        Set<Pcs<ITree>> t1 = GumTreePcsBuilder.fromGumTree(left, Revision.LEFT);
        Set<Pcs<ITree>> t2 = GumTreePcsBuilder.fromGumTree(right, Revision.RIGHT);

        Map<ITree, ITree> classRepMap = initializeClassRepresentativesMap(base);
        mapToClassRepresentatives(left, matchLeft.getMappings(), classRepMap, Revision.LEFT);
        mapToClassRepresentatives(right, matchRight.getMappings(), classRepMap, Revision.RIGHT);
        augmentClassRepresentatives(leftRight.getMappings(), classRepMap);

        TStar<ITree> delta = new TStar<ITree>(classRepMap, ITree::getLabel, t0, t1, t2);
        TStar<ITree> t0Star = new TStar<ITree>(classRepMap, ITree::getLabel, t0);

        TdmMerge.resolveRawMerge(t0Star, delta);

        return delta;
    }

    /**
     * Augment the class representatives match with left-right matching information. Any node originating from the
     * RIGHT revision that is mapped to itself and is matched to a node in the LEFT revision is remapped to the LEFT
     * node.
     *
     * This solves conflicts originating from identical additions in both revisions.
     */
    private static void augmentClassRepresentatives(MappingStore matchLeftRight, Map<ITree, ITree> classRepMap) {
        for (Map.Entry<ITree, ITree> entry : classRepMap.entrySet()) {
            ITree node = entry.getKey();
            ITree classRep = entry.getValue();

            if (node == classRep && node.getMetadata(TdmMerge.REV) == Revision.RIGHT) {
                // unmapped right node, map to left if mapping exists
                ITree leftClassRep = matchLeftRight.getSrc(node);
                if (leftClassRep != null)
                    classRepMap.put(node, leftClassRep);
            }
        }
    }

    /**
     * Add the base tree's self-mappings to the class representatives map.
     */
    private static Map<ITree, ITree> initializeClassRepresentativesMap(ITree base) {
        Map<ITree, ITree> classRepMap = new HashMap<>();
        for (ITree tree : base.preOrder()) {
            tree.setMetadata(TdmMerge.REV, Revision.BASE);
            classRepMap.put(tree, tree);
        }
        return classRepMap;
    }

    /**
     * Map nodes in a revision to its class representative.
     */
    private static void mapToClassRepresentatives(ITree tree, MappingStore mappings, Map<ITree, ITree> classRepMap, Revision rev) {
        for (ITree t : tree.preOrder()) {
            t.setMetadata(TdmMerge.REV, rev);
            if (mappings.hasDst(t)) {
                classRepMap.put(t, mappings.getSrc(t));
            } else {
                classRepMap.put(t, t);
            }
        }
    }
}
