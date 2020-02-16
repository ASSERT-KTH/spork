package se.kth.spork.merge.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TStar;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.*;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Spoon specialization of the 3DM merge algorithm.
 *
 * @author Simon Larsén
 */
public class Spoon3dmMerge {

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should be the best common
     * ancestor of left and right.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @return The merge of left and right.
     */
    public static CtClass<?> merge(CtClass<?> base, CtClass<?> left, CtClass<?> right) {
        System.out.println("Converting to GumTree");
        ITree baseGumtree = new SpoonGumTreeBuilder().getTree(base);
        ITree leftGumtree = new SpoonGumTreeBuilder().getTree(left);
        ITree rightGumtree = new SpoonGumTreeBuilder().getTree(right);

        System.out.println("Matching trees");
        Matcher baseLeftGumtreeMatch = matchTrees(baseGumtree, leftGumtree);
        Matcher baseRightGumtreeMatch = matchTrees(baseGumtree, rightGumtree);
        Matcher leftRightGumtreeMatch = matchTrees(leftGumtree, rightGumtree);

        System.out.println("Converting matches to Spoon matches");
        SpoonMapping baseLeft = SpoonMapping.fromGumTreeMapping(baseLeftGumtreeMatch.getMappings());
        SpoonMapping baseRight = SpoonMapping.fromGumTreeMapping(baseRightGumtreeMatch.getMappings());
        SpoonMapping leftRight = SpoonMapping.fromGumTreeMapping(leftRightGumtreeMatch.getMappings());

        System.out.println("Mapping to class representatives");
        Map<CtWrapper, CtWrapper> classRepMap = createClassRepresentativesMapping(
                base, left, right, baseLeft, baseRight, leftRight);

        System.out.println("Converting to PCS");
        Set<Pcs<CtWrapper>> t0 = SpoonPcs.fromSpoon(base, Revision.BASE);
        Set<Pcs<CtWrapper>> t1 = SpoonPcs.fromSpoon(left, Revision.LEFT);
        Set<Pcs<CtWrapper>> t2 = SpoonPcs.fromSpoon(right, Revision.RIGHT);

        System.out.println("Computing raw merge");
        TStar<CtWrapper> delta = new TStar<>(classRepMap, new GetContent(), t0, t1, t2);
        TStar<CtWrapper> t0Star = new TStar<>(classRepMap, new GetContent(), t0);

        System.out.println("Resolving raw merge");
        TdmMerge.resolveRawMerge(t0Star, delta);

        System.out.println("Interpreting PCS");
        return SpoonPcs.fromPcs(delta.getStar(), delta.getContents(), baseLeft, baseRight);
    }

    /**
     * This class determines what the content of any given type of node is.
     */
    private static class GetContent implements Function<CtWrapper, Object> {

        /**
         * Return the content of the supplied node. For example, the content of a CtLiteral is its value.
         * <p>
         * TODO extract more types of content
         *
         * @param wrapper A wrapped Spoon node.
         * @return The content of the node.
         */
        @Override
        public Object apply(CtWrapper wrapper) {
            if (wrapper == null)
                return null;

            CtElement elem = wrapper.getElement();
            if (elem instanceof CtModule || elem instanceof CtPackage) {
                return null;
            } else if (elem instanceof CtLiteral) {
                return ((CtLiteral<?>) elem).getValue();
            }
            return elem.getShortRepresentation();
        }
    }

    /**
     * Create the class representatives mapping. The class representatives for the different revisions are defined as:
     *
     * 1. A node NB in base is its own class representative.
     * 2. The class representative of a node NL in left is NB if there exists a tree matching NL -> NB in the baseLeft
     *    matching. Otherwise it is NL.
     * 3. The class representative of a node NR in right is NB if there exists a tree matching NR -> NB in the baseRight
     *    matching. If that is not the case, the class representative es NL if there exists a matching NL -> NR in
     *    leftRight. Otherwise it is NR.
     *
     * Put briefly, base nodes are always mapped to themselves, nodes in left are mapped to base nodes if they are
     * matched, and nodes in right are mapped to base nodes or left nodes if they are matched, with base matchings
     * having priority.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @param baseLeft A matching from base to left.
     * @param baseRight A matching from base to right.
     * @param leftRight A matching from left to right.
     * @return The class representatives map.
     */
    private static Map<CtWrapper, CtWrapper> createClassRepresentativesMapping(
            CtClass<?> base,
            CtClass<?> left,
            CtClass<?> right,
            SpoonMapping baseLeft,
            SpoonMapping baseRight,
            SpoonMapping leftRight) {
        Map<CtWrapper, CtWrapper> classRepMap = initializeClassRepresentatives(base);
        mapToClassRepresentatives(left, baseLeft, classRepMap, Revision.LEFT);
        mapToClassRepresentatives(right, baseRight, classRepMap, Revision.RIGHT);
        augmentClassRepresentatives(leftRight, classRepMap);
        return classRepMap;
    }

    /**
     * Initialize the class representatives map by mapping each element in base to itself.
     *
     * @param base The base revision of the trees to be merged.
     * @return An initialized class representatives map.
     */
    private static Map<CtWrapper, CtWrapper> initializeClassRepresentatives(CtElement base) {
        Map<CtWrapper, CtWrapper> classRepMap = new HashMap<>();
        Iterator<CtElement> descIt = base.descendantIterator();
        while (descIt.hasNext()) {
            CtElement tree = descIt.next();
            tree.putMetadata(TdmMerge.REV, Revision.BASE);
            CtWrapper wrapped = WrapperFactory.wrap(tree);
            classRepMap.put(wrapped, wrapped);
        }
        return classRepMap;
    }

    /**
     * Map the nodes of a tree revision (left or right) to their corresponding class representatives. For example, if a
     * node NL in the left revision is matched to a node NB in the base revision, then the mapping NL -> NB is entered
     * into the class representatives map.
     * <p>
     * This method also attaches the tree's revision to each node in the tree.
     * <p>
     * TODO move attaching of the tree revision somewhere else, it's super obtuse to have here.
     *
     * @param tree        A revision of the trees to be merged (left or right).
     * @param mappings    A tree matching from the base revision to the provided tree.
     * @param classRepMap The class representatives map.
     * @param rev         The provided tree's revision.
     */
    private static void mapToClassRepresentatives(CtElement tree, SpoonMapping mappings, Map<CtWrapper, CtWrapper> classRepMap, Revision rev) {
        Iterator<CtElement> descIt = tree.descendantIterator();
        while (descIt.hasNext()) {
            CtElement t = descIt.next();
            t.putMetadata(TdmMerge.REV, rev);
            CtWrapper wrapped = WrapperFactory.wrap(t);

            if (mappings.hasDst(wrapped)) {
                classRepMap.put(wrapped, mappings.getSrc(wrapped));
            } else {
                classRepMap.put(wrapped, wrapped);
            }
        }
    }

    /**
     * Augment the class representatives map with mappings between the non-base revisions. This helps alleviate
     * problems that occur when code chunks have been copy-pasted between different revisions. For example, if
     * both left and right have added the exact same method that does not exist in base, this step will help resolving
     * that inconsistency.
     *
     * @param leftRight   A tree matching from the left revision to the right revision.
     * @param classRepMap The class representatives map.
     */
    private static void augmentClassRepresentatives(SpoonMapping leftRight, Map<CtWrapper, CtWrapper> classRepMap) {
        for (Map.Entry<CtWrapper, CtWrapper> entry : classRepMap.entrySet()) {
            CtWrapper node = entry.getKey();
            Revision rev = (Revision) node.getElement().getMetadata(TdmMerge.REV);
            CtWrapper classRep = entry.getValue();

            if (node == classRep && rev == Revision.RIGHT) {
                CtWrapper leftClassRep = leftRight.getSrc(node);
                if (leftClassRep != null)
                    classRepMap.put(node, leftClassRep);
            }
        }
    }

    private static Matcher matchTrees(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher(src, dst);
        matcher.match();
        return matcher;
    }
}
