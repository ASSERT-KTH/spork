package se.kth.spork.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.base3dm.*;
import se.kth.spork.util.Triple;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.declaration.*;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        long start = System.nanoTime();
        LOGGER.info("Parsing files to Spoon trees");

        CtElement baseTree = Parser.parse(base);
        CtElement leftTree = Parser.parse(left);
        CtElement rightTree = Parser.parse(right);

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
     * @return The merge of left and right.
     */
    public static CtElement merge(CtElement base, CtElement left, CtElement right) {
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
        TStar<SpoonNode, RoledValue> delta = new TStar<>(classRepMap, new GetContent(), t0, t1, t2);
        TStar<SpoonNode, RoledValue> t0Star = new TStar<>(classRepMap, new GetContent(), t0);

        LOGGER.info("Resolving final PCS merge");
        TdmMerge.resolveRawMerge(t0Star, delta);
        handleContentConflicts(delta);

        LOGGER.info("Interpreting resolved PCS merge");
        CtElement merge = PcsInterpreter.fromMergedPcs(delta, baseLeft, baseRight);

        LOGGER.info("Merging import statements");
        List<CtImport> mergedImports = mergeImportStatements(base, left, right);
        merge.putMetadata(Parser.IMPORT_STATEMENTS, mergedImports);

        LOGGER.info("Merged in " + (double) (System.nanoTime() - start) / 1e9 + " seconds");

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
            if (wrapper == null || wrapper.getElement() == null)
                return null;

            CtElement elem = wrapper.getElement();
            RoledValue rv = extractPrimaryValue(elem);
            attachSecondaryValues(elem, rv);

            return rv;
        }

        private static RoledValue extractPrimaryValue(CtElement elem) {
            if (elem instanceof CtLiteral) {
                CtLiteral<?> lit = (CtLiteral<?>) elem;
                return new RoledValue(lit.getValue(), CtRole.VALUE);
            } else if (elem instanceof CtReference) {
                CtReference ref = (CtReference) elem;
                return new RoledValue(ref.getSimpleName(), CtRole.NAME);
            } else if (elem instanceof CtNamedElement) {
                CtNamedElement namedElem = (CtNamedElement) elem;
                return new RoledValue(namedElem.getSimpleName(), CtRole.NAME);
            } else if (elem instanceof CtOperatorAssignment) {
                CtOperatorAssignment<?, ?> op = (CtOperatorAssignment<?, ?>) elem;
                return new RoledValue(op.getKind(), CtRole.OPERATOR_KIND);
            } else if (elem instanceof CtBinaryOperator) {
                CtBinaryOperator<?> op = (CtBinaryOperator<?>) elem;
                return new RoledValue(op.getKind(), CtRole.OPERATOR_KIND);
            } else if (elem instanceof CtUnaryOperator) {
                CtUnaryOperator<?> op = (CtUnaryOperator<?>) elem;
                return new RoledValue(op.getKind(), CtRole.OPERATOR_KIND);
            }

            return new RoledValue(elem.getShortRepresentation(), null);
        }

        private static void attachSecondaryValues(CtElement elem, RoledValue rv) {
            if (elem instanceof CtModifiable) {
                Set<ModifierKind> modifiers = new HashSet<>(elem.getValueByRole(CtRole.MODIFIER));
                rv.addSecondaryValue(modifiers, CtRole.MODIFIER);
            } else if (elem instanceof CtWildcardReference) {
                rv.addSecondaryValue(elem.getValueByRole(CtRole.IS_UPPER), CtRole.IS_UPPER);
            }
        }
    }

    /**
     * Try to automatically resolve content conflicts in delta.
     *
     * @param delta A merge.
     */
    private static void handleContentConflicts(TStar<SpoonNode, RoledValue> delta) {
        for (Pcs<SpoonNode> pcs : delta.getStar()) {
            SpoonNode pred = pcs.getPredecessor();
            Set<Content<SpoonNode, RoledValue>> nodeContents = delta.getContent(pred);

            if (nodeContents.size() > 1) {
                Triple<Optional<Content<SpoonNode, RoledValue>>, Content<SpoonNode, RoledValue>, Content<SpoonNode, RoledValue>> revisions = getContentRevisions(nodeContents);
                Optional<Content<SpoonNode, RoledValue>> baseOpt = revisions.first;
                Content<SpoonNode, RoledValue> left = revisions.second;
                RoledValue leftVal = revisions.second.getValue();
                RoledValue rightVal = revisions.third.getValue();

                if (leftVal.hasSecondaryValues() || rightVal.hasSecondaryValues()) {
                    // can currently only resolve content conflicts that occur with modifier lists
                    if (left.getContext().getPredecessor().getElement() instanceof CtModifiable) {
                        Optional<RoledValue> merged = mergeModifiable(baseOpt.map(Content::getValue), leftVal, rightVal);

                        if (merged.isPresent()) {
                            Set<Content<SpoonNode, RoledValue>> newContents = new HashSet<>();
                            newContents.add(new Content<>(left.getContext(), merged.get()));
                            delta.setContent(pred, newContents);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<RoledValue> mergeModifiable(Optional<RoledValue> base, RoledValue left, RoledValue right) {
        // all revisions must have the same primary value
        if (!left.getValue().equals(right.getValue())
                || (base.isPresent() && !base.get().getValue().equals(left.getValue()))
        )
            return Optional.empty();

        Set<ModifierKind> baseModifiers = new HashSet<>();
        base.ifPresent(rv ->
                baseModifiers.addAll((Set<ModifierKind>) rv.getSecondaryByRole(CtRole.MODIFIER).getValue())
        );

        Set<ModifierKind> leftModifiers = (Set<ModifierKind>) left.getSecondaryByRole(CtRole.MODIFIER).getValue();
        Set<ModifierKind> rightModifiers = (Set<ModifierKind>) right.getSecondaryByRole(CtRole.MODIFIER).getValue();

        Set<ModifierKind> visibility = new HashSet<>();
        Set<ModifierKind> keywords = new HashSet<>();
        Set<ModifierKind> other = new HashSet<>();

        Stream.of(baseModifiers, leftModifiers, rightModifiers).flatMap(Set::stream).forEach(mod -> {
            switch (mod) {
                // visibility
                case PRIVATE:
                case PUBLIC:
                case PROTECTED:
                    visibility.add(mod);
                    break;
                // keywords
                case ABSTRACT:
                case FINAL:
                    keywords.add(mod);
                    break;
                default:
                    other.add(mod);
                    break;
            }
        });

        if (visibility.size() > 1) {
            visibility.removeIf(baseModifiers::contains);
        }
        // visibility is the only place where we can have obvious addition conflcits
        // TODO further analyze conflicts among other modifiers (e.g. you can't combine static and volatile)
        if (visibility.size() != 1) {
            return Optional.empty();
        }

        Set<ModifierKind> mods = Stream.of(visibility, keywords, other).flatMap(Set::stream)
                .filter(mod ->
                        // present in both left and right == ALL GOOD
                        leftModifiers.contains(mod) && rightModifiers.contains(mod) ||
                                // respect deletions, if an element is present in only one of left and right, and is
                                // present in base, then it has been deleted
                                (leftModifiers.contains(mod) ^ rightModifiers.contains(mod)) && !baseModifiers.contains(mod)
                )
                .collect(Collectors.toSet());

        RoledValue merge = new RoledValue(left.getValue(), left.getRole());
        merge.addSecondaryValue(mods, CtRole.MODIFIER);
        return Optional.of(merge);
    }

    /**
     * Extract base, left and right revisions from the set of contents.
     *
     * @param contents A set of contents with precisely three elements.
     * @return A triple with base in the first slot, left in the second and right in the third.
     */
    private static Triple<Optional<Content<SpoonNode, RoledValue>>, Content<SpoonNode, RoledValue>, Content<SpoonNode, RoledValue>> getContentRevisions(Set<Content<SpoonNode, RoledValue>> contents) {
        Content<SpoonNode, RoledValue> base = null;
        Content<SpoonNode, RoledValue> left = null;
        Content<SpoonNode, RoledValue> right = null;

        for (Content<SpoonNode, RoledValue> cnt : contents) {
            switch (cnt.getContext().getRevision()) {
                case BASE:
                    base = cnt;
                    break;
                case LEFT:
                    left = cnt;
                    break;
                case RIGHT:
                    right = cnt;
                    break;
            }
        }

        if (left == null || right == null)
            throw new IllegalStateException("Expected at least left and right revisions, got: " + contents);

        return new Triple<>(Optional.ofNullable(base), left, right);
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
