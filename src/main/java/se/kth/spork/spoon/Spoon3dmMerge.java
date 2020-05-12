package se.kth.spork.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.base3dm.*;
import se.kth.spork.spoon.matching.ClassRepresentatives;
import se.kth.spork.spoon.matching.MappingRemover;
import se.kth.spork.spoon.matching.SpoonMapping;
import se.kth.spork.spoon.pcsinterpreter.PcsInterpreter;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.LazyLogger;
import se.kth.spork.util.Pair;
import spoon.reflect.declaration.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Spoon specialization of the 3DM merge algorithm.
 *
 * @author Simon Larsén
 */
public class Spoon3dmMerge {
    private static final LazyLogger LOGGER = new LazyLogger(Spoon3dmMerge.class);

    /**
     * Merge the left and right revisions with an AST-based merge.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    public static Pair<CtModule, Integer> merge(Path base, Path left, Path right) {
        long start = System.nanoTime();

        // PARSING PHASE
        LOGGER.info(() -> "Parsing files to Spoon trees");
        CtModule baseTree = Parser.parse(base);
        CtModule leftTree = Parser.parse(left);
        CtModule rightTree = Parser.parse(right);

        long end = System.nanoTime();
        double timeDelta = (double) (end - start) / 1e9;
        LOGGER.info(() -> "Parsed files in " + timeDelta + " seconds");

        return merge(baseTree, leftTree, rightTree);
    }

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should be the best common
     * ancestor of left and right.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    public static <T extends CtElement> Pair<T, Integer> merge(T base, T left, T right) {
        long start = System.nanoTime();

        // MATCHING PHASE
        LOGGER.info(() -> "Converting to GumTree trees");
        ITree baseGumtree = new SpoonGumTreeBuilder().getTree(base);
        ITree leftGumtree = new SpoonGumTreeBuilder().getTree(left);
        ITree rightGumtree = new SpoonGumTreeBuilder().getTree(right);

        LOGGER.info(() -> "Matching trees with GumTree");
        Matcher baseLeftGumtreeMatch = matchTrees(baseGumtree, leftGumtree);
        Matcher baseRightGumtreeMatch = matchTrees(baseGumtree, rightGumtree);
        Matcher leftRightGumtreeMatch = matchTreesTopDown(leftGumtree, rightGumtree);

        LOGGER.info(() -> "Converting GumTree matches to Spoon matches");
        SpoonMapping baseLeft = SpoonMapping.fromGumTreeMapping(baseLeftGumtreeMatch.getMappings());
        SpoonMapping baseRight = SpoonMapping.fromGumTreeMapping(baseRightGumtreeMatch.getMappings());
        SpoonMapping leftRight = SpoonMapping.fromGumTreeMapping(leftRightGumtreeMatch.getMappings());

        // 3DM PHASE
        LOGGER.info(() -> "Mapping nodes to class representatives");
        Map<SpoonNode, SpoonNode> classRepMap = ClassRepresentatives.createClassRepresentativesMapping(
                base, left, right, baseLeft, baseRight, leftRight);

        LOGGER.info(() -> "Converting Spoon trees to PCS triples");
        Set<Pcs<SpoonNode>> t0 = PcsBuilder.fromSpoon(base, Revision.BASE);
        Set<Pcs<SpoonNode>> t1 = PcsBuilder.fromSpoon(left, Revision.LEFT);
        Set<Pcs<SpoonNode>> t2 = PcsBuilder.fromSpoon(right, Revision.RIGHT);

        LOGGER.info(() -> "Computing raw PCS merge");
        ChangeSet<SpoonNode, RoledValues> delta = new ChangeSet<>(classRepMap, new ContentResolver(), t0, t1, t2);
        ChangeSet<SpoonNode, RoledValues> t0Star = new ChangeSet<>(classRepMap, new ContentResolver(), t0);

        LOGGER.info(() -> "Resolving final PCS merge");
        TdmMerge.resolveRawMerge(t0Star, delta);

        Set<SpoonNode> rootConflictingNodes = StructuralConflict.extractRootConflictingNodes(delta.getStructuralConflicts());
        if (!rootConflictingNodes.isEmpty()) {
            LOGGER.info(() -> "Root conflicts detected, restarting merge");
            LOGGER.info(() -> "Removing root conflicting nodes from tree matchings");
            MappingRemover.removeFromMappings(rootConflictingNodes, baseLeft, baseRight, leftRight);

            LOGGER.info(() -> "Mapping nodes to class representatives");
            classRepMap = ClassRepresentatives.createClassRepresentativesMapping(
                    base, left, right, baseLeft, baseRight, leftRight);

            LOGGER.info(() -> "Computing raw PCS merge");
            delta = new ChangeSet<>(classRepMap, new ContentResolver(), t0, t1, t2);

            LOGGER.info(() -> "Resolving final PCS merge");
            TdmMerge.resolveRawMerge(t0Star, delta);
        }

        // INTERPRETER PHASE
        LOGGER.info(() -> "Interpreting resolved PCS merge");
        Pair<CtElement, Integer> merge = PcsInterpreter.fromMergedPcs(delta, baseLeft, baseRight);
        // we can be certain that the merge tree has the same root type as the three constituents, so this cast is safe
        @SuppressWarnings("unchecked")
        T mergeTree = (T) merge.first;
        int numConflicts = merge.second;


        LOGGER.info(() -> "Merging import statements");
        List<CtImport> mergedImports = mergeImportStatements(base, left, right);
        mergeTree.putMetadata(Parser.IMPORT_STATEMENTS, mergedImports);

        LOGGER.info(() -> "Merged in " + (double) (System.nanoTime() - start) / 1e9 + " seconds");

        return Pair.of(mergeTree, numConflicts);
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
