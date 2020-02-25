package se.kth.spork.merge.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TStar;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtNamedElement;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtReference;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * Spoon specialization of the 3DM merge algorithm.
 *
 * @author Simon Larsén
 */
public class Spoon3dmMerge {
    private static final Logger LOGGER = LoggerFactory.getLogger(Spoon3dmMerge.class);

    /**
     * Merge the left and right revisions with an AST-based merge.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return A merged Spoon tree.
     */
    public static CtElement merge(Path base, Path left, Path right) {
        CtElement baseTree = Parser.parse(base);
        CtElement leftTree = Parser.parse(left);
        CtElement rightTree = Parser.parse(right);

        return merge(baseTree, leftTree, rightTree);
    }

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should be the best common
     * ancestor of left and right.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return The merge of left and right.
     */
    public static CtElement merge(CtElement base, CtElement left, CtElement right) {
        LOGGER.info("Converting to GumTree trees");
        ITree baseGumtree = new SpoonGumTreeBuilder().getTree(base);
        ITree leftGumtree = new SpoonGumTreeBuilder().getTree(left);
        ITree rightGumtree = new SpoonGumTreeBuilder().getTree(right);

        LOGGER.info("Matching trees with GumTree");
        Matcher baseLeftGumtreeMatch = matchTrees(baseGumtree, leftGumtree);
        Matcher baseRightGumtreeMatch = matchTrees(baseGumtree, rightGumtree);
        Matcher leftRightGumtreeMatch = matchTreesTopDown(leftGumtree, rightGumtree);

        LOGGER.info("Converting GumTree matches to Spoon matches");
        SpoonMapping baseLeft = SpoonMapping.fromGumTreeMapping(baseLeftGumtreeMatch.getMappings());
        SpoonMapping baseRight = SpoonMapping.fromGumTreeMapping(baseRightGumtreeMatch.getMappings());
        SpoonMapping leftRight = SpoonMapping.fromGumTreeMapping(leftRightGumtreeMatch.getMappings());

        LOGGER.info("Mapping nodes to class representatives");
        Map<SpoonNode, SpoonNode> classRepMap = createClassRepresentativesMapping(
                base, left, right, baseLeft, baseRight, leftRight);

        LOGGER.info("Converting Spoon trees to PCS triples");
        Set<Pcs<SpoonNode>> t0 = SpoonPcs.fromSpoon(base, Revision.BASE);
        Set<Pcs<SpoonNode>> t1 = SpoonPcs.fromSpoon(left, Revision.LEFT);
        Set<Pcs<SpoonNode>> t2 = SpoonPcs.fromSpoon(right, Revision.RIGHT);

        LOGGER.info("Computing raw PCS merge");
        TStar<SpoonNode, RoledValue> delta = new TStar<>(classRepMap, new GetContent(), t0, t1, t2);
        TStar<SpoonNode, RoledValue> t0Star = new TStar<>(classRepMap, new GetContent(), t0);

        LOGGER.info("Resolving final PCS merge");
        TdmMerge.resolveRawMerge(t0Star, delta);

        LOGGER.info("Interpreting resolved PCS merge");
        CtElement merge = SpoonPcs.fromMergedPcs(delta, baseLeft, baseRight);

        LOGGER.info("Merging import statements");
        List<CtImport> mergedImports = mergeImportStatements(base, left, right);
        merge.putMetadata(Parser.IMPORT_STATEMENTS, mergedImports);

        return merge;
    }

    /**
     * Merge import statements from base, left and right. Import statements are expected to be attached
     * to each tree's root node metadata with the {@link Parser#IMPORT_STATEMENTS} key.
     * <p>
     * This method naively merges import statements by respecting additions and deletions from both revisions.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return A merged import list, sorted in lexicographical order.
     */
    @SuppressWarnings("unchecked")
    private static List<CtImport> mergeImportStatements(CtElement base, CtElement left, CtElement right) {
        Set<CtImport> baseImports = new HashSet<>((Collection<CtImport>) base.getMetadata(Parser.IMPORT_STATEMENTS));
        Set<CtImport> leftImports = new HashSet<>((Collection<CtImport>) left.getMetadata(Parser.IMPORT_STATEMENTS));
        Set<CtImport> rightImports = new HashSet<>((Collection<CtImport>) right.getMetadata(Parser.IMPORT_STATEMENTS));
        Set<CtImport> merge = new HashSet<>();

        // first create union, this respects all additions
        merge.addAll(baseImports);
        merge.addAll(leftImports);
        merge.addAll(rightImports);

        // now remove all elements that were deleted
        Set<CtImport> baseLeftDeletions = new HashSet<>(baseImports);
        baseLeftDeletions.removeAll(leftImports);
        Set<CtImport> baseRightDeletions = new HashSet<>(baseImports);
        baseRightDeletions.removeAll(rightImports);

        merge.removeAll(baseLeftDeletions);
        merge.removeAll(baseRightDeletions);

        List<CtImport> ret = new ArrayList<>(merge);
        ret.sort(Comparator.comparing(CtImport::toString));
        return ret;
    }

    /**
     * This class determines what the content of any given type of node is.
     */
    private static class GetContent implements Function<SpoonNode, RoledValue> {

        /**
         * Return the content of the supplied node. For example, the content of a CtLiteral is its value, and the
         * content of a CtNamedElement is its simple name.
         *
         * @param wrapper A wrapped Spoon node.
         * @return The content of the node.
         */
        @Override
        public RoledValue apply(SpoonNode wrapper) {
            if (wrapper == null)
                return null;

            CtElement elem = wrapper.getElement();
            if (elem instanceof CtLiteral) {
                CtLiteral<?> lit = (CtLiteral<?>) elem;
                return new RoledValue(lit.getValue(), CtRole.VALUE);
            } else if (elem instanceof CtReference) {
                CtReference ref = (CtReference) elem;
                return new RoledValue(ref.getSimpleName(), CtRole.NAME);
            } else if (elem instanceof CtNamedElement) {
                CtNamedElement namedElem = (CtNamedElement) elem;
                return new RoledValue(namedElem.getSimpleName(), CtRole.NAME);
            }
            return new RoledValue(elem.getShortRepresentation(), null);
        }
    }

    /**
     * Create the class representatives mapping. The class representatives for the different revisions are defined as:
     * <p>
     * 1. A node NB in base is its own class representative.
     * 2. The class representative of a node NL in left is NB if there exists a tree matching NL -> NB in the baseLeft
     * matching. Otherwise it is NL.
     * 3. The class representative of a node NR in right is NB if there exists a tree matching NR -> NB in the baseRight
     * matching. If that is not the case, the class representative may be NL if there exists a tree matching
     * NL -> NR. The latter is referred to as an augmentation, and is done conservatively to avoid spurious
     * mappings between left and right revisions. See {@link ClassRepresentativeAugmenter} for more info.
     * <p>
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
    private static Map<SpoonNode, SpoonNode> createClassRepresentativesMapping(
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
    private static void mapToClassRepresentatives(CtElement tree, SpoonMapping mappings, Map<SpoonNode, SpoonNode> classRepMap, Revision rev) {
        Iterator<CtElement> descIt = tree.descendantIterator();
        while (descIt.hasNext()) {
            CtElement t = descIt.next();
            t.putMetadata(TdmMerge.REV, rev);
            SpoonNode wrapped = NodeFactory.wrap(t);

            if (mappings.hasDst(wrapped)) {
                classRepMap.put(wrapped, mappings.getSrc(wrapped));
            } else {
                classRepMap.put(wrapped, wrapped);
            }
        }
    }

    private static Matcher matchTrees(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher(src, dst);
        matcher.match();
        return matcher;
    }

    private static Matcher matchTreesTopDown(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher("gumtree-topdown", src, dst);
        matcher.match();
        return matcher;
    }
}
