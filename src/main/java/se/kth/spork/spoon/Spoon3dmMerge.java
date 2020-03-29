package se.kth.spork.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.base3dm.*;
import se.kth.spork.util.Pair;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtWildcardReference;

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
     * @return A pair on the form (mergeTree, hasConflicts).
     */
    public static Pair<CtModule, Boolean> merge(Path base, Path left, Path right) {
        long start = System.nanoTime();
        LOGGER.info("Parsing files to Spoon trees");

        CtModule baseTree = Parser.parse(base);
        CtModule leftTree = Parser.parse(left);
        CtModule rightTree = Parser.parse(right);

        long end = System.nanoTime();
        double timeDelta = (double) (end - start) / 1e9;
        LOGGER.info("Parsed files in " + timeDelta + " seconds");

        return merge(baseTree, leftTree, rightTree);
    }

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should be the best common
     * ancestor of left and right.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return A pair on the form (mergeTree, hasConflicts).
     */
    public static <T extends CtElement> Pair<T, Boolean> merge(T base, T left, T right) {
        long start = System.nanoTime();

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
        Map<SpoonNode, SpoonNode> classRepMap = ClassRepresentatives.createClassRepresentativesMapping(
                base, left, right, baseLeft, baseRight, leftRight);

        LOGGER.info("Converting Spoon trees to PCS triples");
        Set<Pcs<SpoonNode>> t0 = PcsBuilder.fromSpoon(base, Revision.BASE);
        Set<Pcs<SpoonNode>> t1 = PcsBuilder.fromSpoon(left, Revision.LEFT);
        Set<Pcs<SpoonNode>> t2 = PcsBuilder.fromSpoon(right, Revision.RIGHT);

        LOGGER.info("Computing raw PCS merge");
        ChangeSet<SpoonNode, RoledValues> delta = new ChangeSet<>(classRepMap, new GetContent(), t0, t1, t2);
        ChangeSet<SpoonNode, RoledValues> t0Star = new ChangeSet<>(classRepMap, new GetContent(), t0);

        LOGGER.info("Resolving final PCS merge");
        TdmMerge.resolveRawMerge(t0Star, delta);

        Set<SpoonNode> rootConflictingNodes = extractRootConflictingNodes(delta.getStructuralConflicts());
        if (!rootConflictingNodes.isEmpty()) {
            LOGGER.info("Root conflicts detected, restarting merge");
            LOGGER.info("Removing root conflicting nodes from tree matchings");
            removeFromMappings(rootConflictingNodes, baseLeft, baseRight, leftRight);

            LOGGER.info("Mapping nodes to class representatives");
            classRepMap = ClassRepresentatives.createClassRepresentativesMapping(
                    base, left, right, baseLeft, baseRight, leftRight);

            LOGGER.info("Computing raw PCS merge");
            delta = new ChangeSet<>(classRepMap, new GetContent(), t0, t1, t2);

            LOGGER.info("Resolving final PCS merge");
            TdmMerge.resolveRawMerge(t0Star, delta);
        }

        boolean hasContentConflict = ContentMerger.handleContentConflicts(delta);

        LOGGER.info("Interpreting resolved PCS merge");
        Pair<CtElement, Boolean> merge = PcsInterpreter.fromMergedPcs(delta, baseLeft, baseRight);
        // we can be certain that the merge tree has the same root type as the three constituents, so this cast is safe
        @SuppressWarnings("unchecked")
        T mergeTree = (T) merge.first;
        boolean hasStructuralConflicts = merge.second;

        LOGGER.info("Merging import statements");
        List<CtImport> mergedImports = mergeImportStatements(base, left, right);
        mergeTree.putMetadata(Parser.IMPORT_STATEMENTS, mergedImports);

        LOGGER.info("Merged in " + (double) (System.nanoTime() - start) / 1e9 + " seconds");

        return Pair.of(mergeTree, hasContentConflict || hasStructuralConflicts);
    }

    /**
     * Remove the provided nodes from the mappings, along with all of their descendants and any associated virtual
     * nodes. This is a method of allowing certain forms of conflicts to pass by, such as root conflicts. By removing
     * the mapping, problems with duplicated nodes is removed.
     */
    private static void
    removeFromMappings(Set<SpoonNode> nodes, SpoonMapping baseLeft, SpoonMapping baseRight, SpoonMapping leftRight) {
        MappingRemover baseLeftMappingRemover = new MappingRemover(baseLeft);
        MappingRemover baseRightMappingRemover = new MappingRemover(baseRight);
        MappingRemover leftRightMappingRemover = new MappingRemover(leftRight);

        for (SpoonNode node : nodes) {
            switch (node.getRevision()) {
                case BASE:
                    baseLeftMappingRemover.removeRelatedMappings(node);
                    baseRightMappingRemover.removeRelatedMappings(node);
                    break;
                case LEFT:
                    baseLeftMappingRemover.removeRelatedMappings(node);
                    leftRightMappingRemover.removeRelatedMappings(node);
                    break;
                case RIGHT:
                    baseRightMappingRemover.removeRelatedMappings(node);
                    leftRightMappingRemover.removeRelatedMappings(node);
                    break;
            }
        }
    }

    private static Set<SpoonNode>
    extractRootConflictingNodes(Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts) {
        Set<SpoonNode> toIgnore = new HashSet<>();

        for (Map.Entry<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> entry : structuralConflicts.entrySet()) {
            Pcs<SpoonNode> pcs = entry.getKey();
            for (Pcs<SpoonNode> other : entry.getValue()) {
                if (isRootConflict(pcs, other)) {
                    if (pcs.getPredecessor().equals(other.getPredecessor())) {
                        toIgnore.add(other.getPredecessor());
                    }
                    if (pcs.getSuccessor().equals(other.getSuccessor())) {
                        toIgnore.add(other.getSuccessor());
                    }
                }
            }
        }
        return toIgnore;
    }

    private static boolean isRootConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getRoot(), right.getRoot()) &&
                (Objects.equals(left.getPredecessor(), right.getPredecessor()) ||
                        Objects.equals(left.getSuccessor(), right.getSuccessor()));
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
    private static class GetContent implements Function<SpoonNode, RoledValues> {

        /**
         * Return the content of the supplied node. For example, the content of a CtLiteral is its value, and the
         * content of a CtNamedElement is its simple name.
         *
         * @param wrapper A wrapped Spoon node.
         * @return The content of the node.
         */
        @Override
        public RoledValues apply(SpoonNode wrapper) {
            if (wrapper == null || wrapper.getElement() == null)
                return null;

            CtElement elem = wrapper.getElement();
            RoledValues rvs = new RoledValues(elem);

            // general values
            rvs.add(CtRole.IS_IMPLICIT, elem.isImplicit());

            // element-specific values
            if (elem instanceof CtLiteral) {
                CtLiteral<?> lit = (CtLiteral<?>) elem;
                rvs.add(CtRole.VALUE, lit.getValue());
            } else if (elem instanceof CtReference || elem instanceof CtNamedElement) {
                String name = elem.getValueByRole(CtRole.NAME);
                if (!name.matches("\\d+")) {
                    // Only pick up name if it's not a digit.
                    // A digit implies anonymous function, see https://github.com/kth/spork/issues/86 for why we don't
                    // want those.
                    rvs.add(CtRole.NAME, elem.getValueByRole(CtRole.NAME));
                }
            } else if (elem instanceof CtBinaryOperator || elem instanceof CtUnaryOperator || elem instanceof CtOperatorAssignment) {
                rvs.add(CtRole.OPERATOR_KIND, elem.getValueByRole(CtRole.OPERATOR_KIND));
            }

            if (elem instanceof CtParameter) {
                rvs.add(CtRole.IS_VARARGS, elem.getValueByRole(CtRole.IS_VARARGS));
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
