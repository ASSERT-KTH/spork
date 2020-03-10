package se.kth.spork.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.spork.base3dm.*;
import se.kth.spork.cli.SporkPrettyPrinter;
import se.kth.spork.util.Triple;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtReference;
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
        TStar<SpoonNode, RoledValues> delta = new TStar<>(classRepMap, new GetContent(), t0, t1, t2);
        TStar<SpoonNode, RoledValues> t0Star = new TStar<>(classRepMap, new GetContent(), t0);

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
            RoledValues rvs = new RoledValues();

            if (elem instanceof CtLiteral) {
                CtLiteral<?> lit = (CtLiteral<?>) elem;
                rvs.add(CtRole.VALUE, lit.getValue());
            } else if (elem instanceof CtReference || elem instanceof CtNamedElement) {
                rvs.add(CtRole.NAME, elem.getValueByRole(CtRole.NAME));
            } else if (elem instanceof CtBinaryOperator || elem instanceof CtUnaryOperator || elem instanceof CtOperatorAssignment) {
                rvs.add(CtRole.OPERATOR_KIND, elem.getValueByRole(CtRole.OPERATOR_KIND));
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

                // due to comments relying on the position in the file ElementPrinterHelper.getComments, the position must be
                // stored in metadata instead of in the actual comment. Otherwise, the mixing and matching of
                // sources will cause many comments not to be printed.
                elem.putMetadata("position", elem.getPosition());
                elem.setPosition(SourcePosition.NOPOSITION);
            }

            return rvs;
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleContentConflicts(TStar<SpoonNode, RoledValues> delta) {
        for (Pcs<SpoonNode> pcs : delta.getStar()) {
            SpoonNode pred = pcs.getPredecessor();
            Set<Content<SpoonNode, RoledValues>> nodeContents = delta.getContent(pred);

            if (nodeContents.size() > 1) {
                _ContentTriple revisions = getContentRevisions(nodeContents);
                Optional<Content<SpoonNode, RoledValues>> baseOpt = revisions.first;
                Content<SpoonNode, RoledValues> left = revisions.second;
                Optional<RoledValues> baseRoledValues = baseOpt.map(Content::getValue);
                RoledValues leftRoledValues = revisions.second.getValue();
                RoledValues rightRoledValues = revisions.third.getValue();

                RoledValues mergedRoledValues = new RoledValues(leftRoledValues);

                assert leftRoledValues.size() == rightRoledValues.size();

                Deque<ContentConflict> unresolvedConflicts = new ArrayDeque<>();

                for (int i = 0; i < leftRoledValues.size(); i++) {
                    int finalI = i;
                    RoledValue leftPair = leftRoledValues.get(i);
                    RoledValue rightPair = rightRoledValues.get(i);
                    assert leftPair.getRole() == rightPair.getRole();

                    Optional<Object> baseValOpt = baseOpt.map(Content::getValue).map(rv -> rv.get(finalI))
                            .map(RoledValue::getValue);

                    CtRole role = leftPair.getRole();
                    Object leftVal = leftPair.getValue();
                    Object rightVal = rightPair.getValue();

                    if (leftPair.equals(rightPair)) {
                        // this pair cannot possibly conflict
                        continue;
                    }

                    // left and right pairs differ and are so conflicting
                    // we add them as a conflict, but will later remove it if the conflict can be resolved
                    unresolvedConflicts.push(new ContentConflict(
                                    role,
                                    baseOpt.map(Content::getValue).map(rv -> rv.get(finalI)),
                                    leftPair,
                                    rightPair));


                    Optional<?> merged = Optional.empty();

                    // if either value is equal to base, we keep THE OTHER one
                    if (baseValOpt.isPresent() && baseValOpt.get().equals(leftVal)) {
                        merged = Optional.of(rightVal);
                    } else if (baseValOpt.isPresent() && baseValOpt.get().equals(rightVal)) {
                        merged = Optional.of(leftVal);
                    } else {
                        // we need to actually work for this merge :(
                        switch (role) {
                            case MODIFIER:
                                merged = mergeModifierKinds(
                                        baseValOpt.map(o -> (Set<ModifierKind>) o),
                                        (Set<ModifierKind>) leftVal,
                                        (Set<ModifierKind>) rightVal);
                                break;
                            case NAME:
                            case VALUE:
                                // FIXME This is not a merge, but a conflict embedding which should be done later, FIX
                                merged = Optional.of(SporkPrettyPrinter.START_CONFLICT + "\n"
                                        + leftVal + "\n" + SporkPrettyPrinter.MID_CONFLICT + "\n"
                                        + rightVal + "\n" + SporkPrettyPrinter.END_CONFLICT);
                                break;
                            default:
                                // pass
                        }
                    }



                    if (merged.isPresent()) {
                        mergedRoledValues.set(i, role, merged.get());
                        unresolvedConflicts.pop();
                    }
                }

                if (!unresolvedConflicts.isEmpty()) {
                    // at least one conflict was not resolved
                    pred.getElement().putMetadata(ContentConflict.METADATA_KEY, new ArrayList<>(unresolvedConflicts));
                }

                Set<Content<SpoonNode, RoledValues>> contents = new HashSet<>();
                contents.add(new Content<>(pcs, mergedRoledValues));
                delta.setContent(pred, contents);
            }
        }
    }

    private static Optional<Set<ModifierKind>> mergeModifierKinds(
            Optional<Set<ModifierKind>> base, Set<ModifierKind> left, Set<ModifierKind> right) {
        // all revisions must have the same primary value

        Set<ModifierKind> visibility = new HashSet<>();
        Set<ModifierKind> keywords = new HashSet<>();
        Set<ModifierKind> other = new HashSet<>();

        Set<ModifierKind> baseModifiers = base.orElseGet(HashSet::new);

        Stream.of(baseModifiers, left, right)
                .flatMap(Set::stream)
                .forEach(mod -> {
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

        // visibility is the only place where we can have obvious addition conflicts
        // TODO further analyze conflicts among other modifiers (e.g. you can't combine static and volatile)
        if (visibility.size() != 1) {
            return Optional.empty();
        }

        Set<ModifierKind> mods = Stream.of(visibility, keywords, other).flatMap(Set::stream)
                .filter(mod ->
                        // present in both left and right == ALL GOOD
                        left.contains(mod) && right.contains(mod) ||
                                // respect deletions, if an element is present in only one of left and right, and is
                                // present in base, then it has been deleted
                                (left.contains(mod) ^ right.contains(mod)) && !baseModifiers.contains(mod)
                )
                .collect(Collectors.toSet());

        return Optional.of(mods);
    }

    // this is just a type alias to declutter the getContentRevisions method header
    private static class _ContentTriple extends
            Triple<Optional<Content<SpoonNode, RoledValues>>, Content<SpoonNode, RoledValues>, Content<SpoonNode, RoledValues>> {
        public _ContentTriple(Optional<Content<SpoonNode, RoledValues>> first, Content<SpoonNode, RoledValues> second, Content<SpoonNode, RoledValues> third) {
            super(first, second, third);
        }
    }

    private static _ContentTriple getContentRevisions(Set<Content<SpoonNode, RoledValues>> contents) {
        Content<SpoonNode, RoledValues> base = null;
        Content<SpoonNode, RoledValues> left = null;
        Content<SpoonNode, RoledValues> right = null;

        for (Content<SpoonNode, RoledValues> cnt : contents) {
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

        return new _ContentTriple(Optional.ofNullable(base), left, right);
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
