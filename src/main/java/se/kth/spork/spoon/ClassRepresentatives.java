package se.kth.spork.spoon;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.base3dm.TdmMerge;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Class for creating the class representatives mapping for Spoon nodes. This is a crucial part of the 3DM merge
 * algorithm, see the paper <a href="https://doi.org/10.1145/1030397.1030399">A three-way merge for XML documents</a>
 * for the theory.
 *
 * @author Simon Larsén
 */
class ClassRepresentatives {
    /**
     * Create the class representatives mapping. The class representatives for the different revisions are defined as:
     *
     * 1. A node NB in base is its own class representative.
     * 2. The class representative of a node NL in left is NB if there exists a tree matching NL -> NB in the baseLeft
     * matching. Otherwise it is NL.
     * 3. The class representative of a node NR in right is NB if there exists a tree matching NR -> NB in the baseRight
     * matching. If that is not the case, the class representative may be NL if there exists a tree matching
     * NL -> NR. The latter is referred to as an augmentation, and is done conservatively to avoid spurious
     * mappings between left and right revisions. See {@link ClassRepresentativeAugmenter} for more info.
     *
     * Put briefly, base nodes are always mapped to themselves, nodes in left are mapped to base nodes if they are
     * matched, and nodes in right are mapped to base nodes or left nodes if they are matched, with base matchings
     * having priority.
     *
     * @param base      The base revision.
     * @param left      The left revision.
     * @param right     The right revision.
     * @param baseLeft  A matching from base to left.
     * @param baseRight A matching from base to right.
     * @param leftRight A matching from left to right.
     * @return The class representatives map.
     */
    static Map<SpoonNode, SpoonNode> createClassRepresentativesMapping(
            CtElement base,
            CtElement left,
            CtElement right,
            SpoonMapping baseLeft,
            SpoonMapping baseRight,
            SpoonMapping leftRight) {
        Map<SpoonNode, SpoonNode> classRepMap = initializeClassRepresentatives(base);
        mapToClassRepresentatives(left, baseLeft, classRepMap, Revision.LEFT);
        mapToClassRepresentatives(right, baseRight, classRepMap, Revision.RIGHT);
        new ClassRepresentativeAugmenter(classRepMap, leftRight).scan(left);
        return classRepMap;
    }

    /**
     * Initialize the class representatives map by mapping each element in base to itself.
     *
     * @param base The base revision of the trees to be merged.
     * @return An initialized class representatives map.
     */
    private static Map<SpoonNode, SpoonNode> initializeClassRepresentatives(CtElement base) {
        Map<SpoonNode, SpoonNode> classRepMap = new HashMap<>();
        Iterator<CtElement> descIt = base.descendantIterator();
        while (descIt.hasNext()) {
            CtElement tree = descIt.next();
            tree.putMetadata(TdmMerge.REV, Revision.BASE);
            SpoonNode wrapped = NodeFactory.wrap(tree);

            mapNodes(wrapped, wrapped, classRepMap);
        }

        // and finally the virtual root
        mapNodes(NodeFactory.ROOT, NodeFactory.ROOT, classRepMap);

        return classRepMap;
    }

    /**
     * Map the nodes of a tree revision (left or right) to their corresponding class representatives. For example, if a
     * node NL in the left revision is matched to a node NB in the base revision, then the mapping NL -> NB is entered
     * into the class representatives map.
     *
     * This method also attaches the tree's revision to each node in the tree.
     *
     * TODO move attaching of the tree revision somewhere else, it's super obtuse to have here.
     *
     * @param tree        A revision of the trees to be merged (left or right).
     * @param mappings    A tree matching from the base revision to the provided tree.
     * @param classRepMap The class representatives map.
     * @param rev         The provided tree's revision.
     */
    private static void mapToClassRepresentatives(CtElement tree, SpoonMapping mappings, Map<SpoonNode, SpoonNode> classRepMap, Revision rev) {
        Iterator<CtElement> descIt = tree.descendantIterator();
        while (descIt.hasNext()) {
            CtElement t = descIt.next();
            mapToClassRep(mappings, classRepMap, rev, t);
        }
    }

    private static void mapToClassRep(SpoonMapping mappings, Map<SpoonNode, SpoonNode> classRepMap, Revision rev, CtElement t) {
        t.putMetadata(TdmMerge.REV, rev);
        SpoonNode wrapped = NodeFactory.wrap(t);
        SpoonNode classRep = mappings.getSrc(wrapped);

        if (classRep != null) {
            mapNodes(wrapped, classRep, classRepMap);
        } else {
            mapNodes(wrapped, wrapped, classRepMap);
        }
    }


    /**
     * Map from to to, including the associated virtual nodes.
     *
     * @param from A SpoonNode.
     * @param to A SpoonNode
     * @param classRepMap The class representatives map.
     */
    private static void mapNodes(SpoonNode from, SpoonNode to, Map<SpoonNode, SpoonNode> classRepMap) {
        // map the real nodes
        classRepMap.put(from, to);

        // map the virtual nodes
        SpoonNode fromSOL = NodeFactory.startOfChildList(from);
        SpoonNode toSOL = NodeFactory.startOfChildList(to);
        SpoonNode fromEOL = NodeFactory.endOfChildList(from);
        SpoonNode toEOL = NodeFactory.endOfChildList(to);

        classRepMap.put(fromSOL, toSOL);
        classRepMap.put(fromEOL, toEOL);
    }

    /**
     * A scanner that conservatively expands the class representatives mapping with matches from a left-to-right
     * tree matching. If a node in the left tree is not mapped to base (i.e. self-mapped in the class
     * representatives map), but it is matched with some node in right, then the node in right is mapped
     * to the node in left iff their parents are already mapped, and the node in right is also self-mapped.
     * The node in left remains self-mapped.
     *
     * This must be done by traversing the left tree top-down to allow augmenting mappings to propagate. For
     * example, if both the left and the right revision have added identical methods, then their declarations
     * will be mapped first, and then their contents will be mapped recursively (which is OK as the
     * declarations are now mapped). If one would have started with matches in the bodies of the methods,
     * then these would not be added to the class representatives map as the declarations (i.e. parents)
     * would not yet be mapped.
     *
     * The reason for this conservative use of the left-to-right matchings is that there is otherwise a high
     * probability of unwanted matches. For example, if the left revision adds a parameter to some method,
     * and the right revision adds an identical parameter to another method, then these may be matched,
     * even though they are not related. If that match is put into the class representatives map, there
     * may be some really strange effects on the merge process.
     *
     * @author Simon Larsén
     */
    private static class ClassRepresentativeAugmenter extends CtScanner {
        private SpoonMapping leftRightMatch;
        private Map<SpoonNode, SpoonNode> classRepMap;

        /**
         * @param classRepMap The class representatives map, initialized with left-to-base and right-to-base mappings.
         * @param leftRightMatch A tree matching between the left and right revisions, where the left revision is the
         *                       source and the right revision the destination.
         */
        public ClassRepresentativeAugmenter(Map<SpoonNode, SpoonNode> classRepMap, SpoonMapping leftRightMatch) {
            this.classRepMap = classRepMap;
            this.leftRightMatch = leftRightMatch;
        }

        /**
         *
         * @param element An element from the left revision.
         */
        @Override
        public void scan(CtElement element) {
            if (element == null)
                return;

            assert element.getMetadata(TdmMerge.REV) == Revision.LEFT;

            SpoonNode left = NodeFactory.wrap(element);
            if (classRepMap.get(left) == left) {
                SpoonNode right = leftRightMatch.getDst(left);

                if (right != null && classRepMap.get(right) == right) {
                    SpoonNode rightParentClassRep = classRepMap.get(right.getParent());
                    SpoonNode leftParentClassRep = classRepMap.get(left.getParent());

                    if (leftParentClassRep == rightParentClassRep) {
                        // map right to left
                        mapNodes(right, left, classRepMap);
                    }
                }
            }
            super.scan(element);
        }
    }
}
