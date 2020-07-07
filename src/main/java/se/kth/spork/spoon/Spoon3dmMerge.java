package se.kth.spork.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.base3dm.*;
import se.kth.spork.spoon.conflict.CommentContentHandler;
import se.kth.spork.spoon.conflict.ContentConflictHandler;
import se.kth.spork.spoon.conflict.IsImplicitHandler;
import se.kth.spork.spoon.conflict.IsUpperHandler;
import se.kth.spork.spoon.conflict.ModifierHandler;
import se.kth.spork.spoon.conflict.OptimisticInsertInsertHandler;
import se.kth.spork.spoon.conflict.StructuralConflict;
import se.kth.spork.spoon.matching.ClassRepresentatives;
import se.kth.spork.spoon.matching.MappingRemover;
import se.kth.spork.spoon.matching.SpoonMapping;
import se.kth.spork.spoon.conflict.MethodOrderingConflictHandler;
import se.kth.spork.spoon.pcsinterpreter.PcsInterpreter;
import se.kth.spork.spoon.conflict.StructuralConflictHandler;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.LazyLogger;
import se.kth.spork.util.LineBasedMerge;
import se.kth.spork.util.Pair;
import spoon.reflect.declaration.*;
import spoon.reflect.path.CtRole;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Spoon specialization of the 3DM merge algorithm.
 *
 * @author Simon Larsén
 */
public class Spoon3dmMerge {
    private static final LazyLogger LOGGER = new LazyLogger(Spoon3dmMerge.class);

    static {
        System.setProperty("gt.xym.sim", "0.7");
    }

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
     * @param baseMatcher Function that returns a matcher for the base-to-left and base-to-right matchings.
     * @param leftRightMatcher Function that returns a matcher for the left-to-right matching.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    public static <T extends CtElement> Pair<T, Integer> merge(
            T base,
            T left,
            T right,
            BiFunction<ITree, ITree, Matcher> baseMatcher,
            BiFunction<ITree, ITree, Matcher> leftRightMatcher) {
        long start = System.nanoTime();

        // MATCHING PHASE
        LOGGER.info(() -> "Converting to GumTree trees");
        ITree baseGumtree = new SpoonGumTreeBuilder().getTree(base);
        ITree leftGumtree = new SpoonGumTreeBuilder().getTree(left);
        ITree rightGumtree = new SpoonGumTreeBuilder().getTree(right);

        LOGGER.info(() -> "Matching trees with GumTree");
        Matcher baseLeftGumtreeMatch = baseMatcher.apply(baseGumtree, leftGumtree);
        Matcher baseRightGumtreeMatch = baseMatcher.apply(baseGumtree, rightGumtree);
        Matcher leftRightGumtreeMatch = leftRightMatcher.apply(leftGumtree, rightGumtree);

        LOGGER.info(() -> "Converting GumTree matches to Spoon matches");
        SpoonMapping baseLeft = SpoonMapping.Companion.fromGumTreeMapping(baseLeftGumtreeMatch.getMappings());
        SpoonMapping baseRight = SpoonMapping.Companion.fromGumTreeMapping(baseRightGumtreeMatch.getMappings());
        SpoonMapping leftRight = SpoonMapping.Companion.fromGumTreeMapping(leftRightGumtreeMatch.getMappings());

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
        TdmMergeKt.resolveRawMerge(t0Star, delta);

        Set<SpoonNode> rootConflictingNodes = StructuralConflict.extractRootConflictingNodes(delta.getStructuralConflicts());
        if (!rootConflictingNodes.isEmpty()) {
            LOGGER.info(() -> "Root conflicts detected, restarting merge");
            LOGGER.info(() -> "Removing root conflicting nodes from tree matchings");
            MappingRemover.Companion.removeFromMappings(rootConflictingNodes, baseLeft, baseRight, leftRight);

            LOGGER.info(() -> "Mapping nodes to class representatives");
            classRepMap = ClassRepresentatives.createClassRepresentativesMapping(
                    base, left, right, baseLeft, baseRight, leftRight);

            LOGGER.info(() -> "Computing raw PCS merge");
            delta = new ChangeSet<>(classRepMap, new ContentResolver(), t0, t1, t2);

            LOGGER.info(() -> "Resolving final PCS merge");
            TdmMergeKt.resolveRawMerge(t0Star, delta);
        }

        // INTERPRETER PHASE
        LOGGER.info(() -> "Interpreting resolved PCS merge");
        List<StructuralConflictHandler> structuralConflictHandlers = Arrays.asList(
                new MethodOrderingConflictHandler(), new OptimisticInsertInsertHandler());
        List<ContentConflictHandler> contentConflictHandlers = Arrays.asList(
                new IsImplicitHandler(), new ModifierHandler(), new IsUpperHandler(), new CommentContentHandler());
        Pair<CtElement, Integer> merge = PcsInterpreter.fromMergedPcs(
                delta, baseLeft, baseRight, structuralConflictHandlers, contentConflictHandlers);
        // we can be certain that the merge tree has the same root type as the three constituents, so this cast is safe
        @SuppressWarnings("unchecked")
        T mergeTree = (T) merge.first;
        int numConflicts = merge.second;

        int metadataElementConflicts = mergeMetadataElements(mergeTree, base, left, right);

        LOGGER.info(() -> "Checking for duplicated members");
        int duplicateMemberConflicts = eliminateDuplicateMembers(mergeTree);

        LOGGER.info(() -> "Merged in " + (double) (System.nanoTime() - start) / 1e9 + " seconds");

        return Pair.of(mergeTree, numConflicts + metadataElementConflicts + duplicateMemberConflicts);
    }

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should be the best common
     * ancestor of left and right.
     *
     * Uses the full GumTree matcher for base-to-left and base-to-right, and the XY matcher for left-to-right matchings.
     *
     * @param base  The base revision.
     * @param left  The left revision.
     * @param right The right revision.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    public static <T extends CtElement> Pair<T, Integer> merge(T base, T left, T right) {
        return merge(base, left, right, Spoon3dmMerge::matchTrees, Spoon3dmMerge::matchTreesXY);
    }

    private static int mergeMetadataElements(CtElement mergeTree, CtElement base, CtElement left, CtElement right) {
        int numConflicts = 0;

        if (base.getMetadata(Parser.IMPORT_STATEMENTS) != null) {
            LOGGER.info(() -> "Merging import statements");
            List<CtImport> mergedImports = mergeImportStatements(base, left, right);
            mergeTree.putMetadata(Parser.IMPORT_STATEMENTS, mergedImports);
        }

        if (base.getMetadata(Parser.COMPILATION_UNIT_COMMENT) != null) {
            LOGGER.info(() -> "Merging compilation unit comments");
            Pair<String, Integer> cuCommentMerge = mergeCuComments(base, left, right);
            numConflicts += cuCommentMerge.second;
            mergeTree.putMetadata(Parser.COMPILATION_UNIT_COMMENT, cuCommentMerge.first);
        }

        return numConflicts;
    }


    private static int eliminateDuplicateMembers(CtElement merge) {
        List<CtType<?>> types = merge.getElements(e -> true);
        int numConflicts = 0;
        for (CtType<?> type : types) {
            numConflicts += eliminateDuplicateMembers(type);
        }
        return numConflicts;
    }

    private static int eliminateDuplicateMembers(CtType<?> type) {
        List<CtTypeMember> members = new ArrayList<>(type.getTypeMembers());
        Map<String, CtTypeMember> memberMap = new HashMap<>();
        int numConflicts = 0;

        for (CtTypeMember member : members) {
            String key;
            if (member instanceof CtMethod<?>) {
                key = ((CtMethod<?>) member).getSignature();
            } else if (member instanceof CtField<?>) {
                key = member.getSimpleName();
            } else if (member instanceof CtType<?>) {
                key = ((CtType<?>) member).getQualifiedName();
            } else {
                continue;
            }

            CtTypeMember duplicate = memberMap.get(key);
            if (duplicate == null) {
                memberMap.put(key, member);
            } else {
                LOGGER.info(() -> "Merging duplicated member " + key);

                // need to clear the metadata from these members to be able to re-run the merge
                member.descendantIterator().forEachRemaining(NodeFactory::clearNonRevisionMetadata);
                duplicate.descendantIterator().forEachRemaining(NodeFactory::clearNonRevisionMetadata);
                CtTypeMember dummyBase = (CtTypeMember) member.clone();
                dummyBase.setParent(type);
                dummyBase.getDirectChildren().forEach(CtElement::delete);

                // we forcibly set the virtual root as parent, as the real parent of these members is outside of the current scope
                NodeFactory.clearNonRevisionMetadata(member);
                NodeFactory.clearNonRevisionMetadata(duplicate);
                NodeFactory.clearNonRevisionMetadata(dummyBase);
                NodeFactory.forceWrap(member, NodeFactory.ROOT);
                NodeFactory.forceWrap(duplicate, NodeFactory.ROOT);
                NodeFactory.forceWrap(dummyBase, NodeFactory.ROOT);

                // use the full gumtree matcher as both base matcher and left-to-right matcher
                Pair<CtTypeMember, Integer> mergePair = merge(dummyBase, member, duplicate, Spoon3dmMerge::matchTrees, Spoon3dmMerge::matchTrees);
                numConflicts += mergePair.second;
                CtTypeMember mergedMember = mergePair.first;

                member.delete();
                duplicate.delete();

                type.addTypeMember(mergedMember);
            }
        }

        return numConflicts;
    }

    /**
     * Perform a line-based merge of the compilation unit comments.
     *
     * @return A pair with the merge and the amount of conflicts.
     */
    private static Pair<String, Integer> mergeCuComments(CtElement base, CtElement left, CtElement right) {
        String baseComment = getCuComment(base);
        String leftComment = getCuComment(left);
        String rightComment = getCuComment(right);
        return LineBasedMerge.merge(baseComment, leftComment, rightComment);
    }

    private static String getCuComment(CtElement mod) {
        String comment = (String) mod.getMetadata(Parser.COMPILATION_UNIT_COMMENT);
        return comment == null ? "" : comment;
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

    private static Matcher matchTreesXY(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher("xy", src, dst);
        matcher.match();
        return matcher;
    }
}
