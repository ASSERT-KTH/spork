package se.kth.spork.spoon;

import se.kth.spork.base3dm.Content;
import se.kth.spork.base3dm.Pcs;
import se.kth.spork.base3dm.ChangeSet;
import se.kth.spork.util.LineBasedMerge;
import se.kth.spork.util.Pair;
import se.kth.spork.util.Triple;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtWildcardReference;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A class for dealing with merging of content.
 *
 * @author Simon Larsén
 */
public class ContentMerger {

    /**
     * Try to resolve content conflicts in the merge.
     *
     * @param delta A merged TStar.
     */
    @SuppressWarnings("unchecked")
    static void handleContentConflicts(ChangeSet<SpoonNode, RoledValues> delta) {
        for (Pcs<SpoonNode> pcs : delta.getPcsSet()) {
            SpoonNode pred = pcs.getPredecessor();
            Set<Content<SpoonNode, RoledValues>> nodeContents = delta.getContent(pred);

            if (nodeContents.size() > 1) {
                _ContentTriple revisions = getContentRevisions(nodeContents);
                Optional<Content<SpoonNode, RoledValues>> baseOpt = revisions.first;
                RoledValues leftRoledValues = revisions.second.getValue();
                RoledValues rightRoledValues = revisions.third.getValue();

                // NOTE: It is important that the left values are copied,
                // by convention the LEFT values should be put into the tree whenever a conflict cannot be resolved
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

                    // sometimes a value can be partially merged (e.g. modifiers), and then we want to be
                    // able to set the merged value, AND flag a conflict.
                    boolean conflictPresent = false;

                    // if either value is equal to base, we keep THE OTHER one
                    if (baseValOpt.isPresent() && baseValOpt.get().equals(leftVal)) {
                        merged = Optional.of(rightVal);
                    } else if (baseValOpt.isPresent() && baseValOpt.get().equals(rightVal)) {
                        merged = Optional.of(leftVal);
                    } else {
                        // we need to actually work for this merge :(
                        switch (role) {
                            case IS_IMPLICIT:
                                if (baseValOpt.isPresent()) {
                                    merged = Optional.of(!(Boolean) baseValOpt.get());
                                } else {
                                    // when in doubt, discard implicitness
                                    merged = Optional.of(false);
                                }
                                break;
                            case MODIFIER:
                                Pair<Boolean, Optional<Set<ModifierKind>>> mergePair = mergeModifierKinds(
                                        baseValOpt.map(o -> (Set<ModifierKind>) o),
                                        (Set<ModifierKind>) leftVal,
                                        (Set<ModifierKind>) rightVal);
                                conflictPresent = mergePair.first;
                                merged = mergePair.second;
                                break;
                            case COMMENT_CONTENT:
                                merged = mergeComments(baseValOpt.orElse(""), leftVal, rightVal);
                                break;
                            case IS_UPPER:
                                merged = mergeIsUpper(
                                        baseOpt.map(c -> c.getValue().getElement()),
                                        leftRoledValues.getElement(),
                                        rightRoledValues.getElement()
                                );
                                break;
                            default:
                                // pass
                        }
                    }


                    if (merged.isPresent()) {
                        mergedRoledValues.set(i, role, merged.get());

                        if (!conflictPresent)
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

    /**
     * Separate modifiers into visibility (public, private, protected), keywords (static, final) and all
     * others.
     *
     * @param modifiers A stream of modifiers.
     * @return A triple with visibility in first, keywords in second and other in third.
     */
    public static Triple<Set<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>>
    categorizeModifiers(Stream<ModifierKind> modifiers) {
        Set<ModifierKind> visibility = new HashSet<>();
        Set<ModifierKind> keywords = new HashSet<>();
        Set<ModifierKind> other = new HashSet<>();

        modifiers.forEach(mod -> {
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

        return Triple.of(visibility, keywords, other);
    }

    /**
     * Separate modifiers into visibility (public, private, protected), keywords (static, final) and all
     * others.
     *
     * @param modifiers A collection of modifiers.
     * @return A triple with visibility in first, keywords in second and other in third.
     */
    public static Triple<Set<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>>
    categorizeModifiers(Collection<ModifierKind> modifiers) {
        return categorizeModifiers(modifiers.stream());
    }

    /**
     * Extract the visibility modifier(s).
     *
     * @param modifiers A collection of modifiers.
     * @return A possibly empty set of visibility modifiers.
     */
    public static Set<ModifierKind> getVisibility(Collection<ModifierKind> modifiers) {
        return categorizeModifiers(modifiers).first;
    }

    private static Optional<?> mergeIsUpper(Optional<CtElement> baseElem, CtElement leftElem, CtElement rightElem) {
        CtWildcardReference left = (CtWildcardReference) leftElem;
        CtWildcardReference right = (CtWildcardReference) rightElem;

        boolean leftBoundIsImplicit = left.getBoundingType().isImplicit();
        boolean rightBoundIsImplicit = right.getBoundingType().isImplicit();

        if (baseElem.isPresent()) {
            CtWildcardReference base = (CtWildcardReference) baseElem.get();
            boolean baseBoundIsImplicit = base.getBoundingType().isImplicit();

            if (leftBoundIsImplicit != rightBoundIsImplicit) {
                // one bound was removed, so we go with whatever is on the bound that is not equal to base
                return Optional.of(baseBoundIsImplicit == leftBoundIsImplicit ? left.isUpper() : right.isUpper());
            }
        } else {
            if (leftBoundIsImplicit != rightBoundIsImplicit) {
                // only one bound implicit, pick isUpper of the explicit one
                return Optional.of(leftBoundIsImplicit ? left.isUpper() : right.isUpper());
            }
        }

        return Optional.empty();
    }

    private static Optional<?> mergeComments(Object base, Object left, Object right) {
        Pair<String, Boolean> merge = LineBasedMerge.merge(base.toString(), left.toString(), right.toString());

        if (merge.second) {
            return Optional.empty();
        }
        return Optional.of(merge.first);
    }

    /**
     * Return a pair (conflict, mergedModifiers).
     * If the conflict value is true, there is a conflict in the visibility modifiers, and the merged value
     * will always be the left one.
     */
    private static Pair<Boolean, Optional<Set<ModifierKind>>>
    mergeModifierKinds(Optional<Set<ModifierKind>> base, Set<ModifierKind> left, Set<ModifierKind> right) {
        Set<ModifierKind> baseModifiers = base.orElseGet(HashSet::new);

        Stream<ModifierKind> modifiers = Stream.of(baseModifiers, left, right).flatMap(Set::stream);
        Triple<Set<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>>
                categorizedMods = categorizeModifiers(modifiers);

        Set<ModifierKind> baseVis = getVisibility(baseModifiers);
        Set<ModifierKind> leftVis = getVisibility(left);
        Set<ModifierKind> rightVis = getVisibility(right);

        Set<ModifierKind> visibility = categorizedMods.first;
        Set<ModifierKind> keywords = categorizedMods.second;
        Set<ModifierKind> other = categorizedMods.third;

        if (visibility.size() > 1) {
            visibility.removeIf(baseModifiers::contains);
        }

        // visibility is the only place where we can have obvious addition conflicts
        // TODO further analyze conflicts among other modifiers (e.g. you can't combine static and volatile)
        boolean conflict = visibility.size() != 1 ||
                !leftVis.equals(rightVis) && !leftVis.equals(baseVis) && !rightVis.equals(baseVis);

        if (conflict) {
            // use left version on conflict to follow the convention
            visibility = leftVis;
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

        return Pair.of(conflict,Optional.of(mods));
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

    // this is just a type alias to declutter the getContentRevisions method header
    private static class _ContentTriple extends Triple<
            Optional<Content<SpoonNode, RoledValues>>,
            Content<SpoonNode, RoledValues>,
            Content<SpoonNode, RoledValues>> {
        public _ContentTriple(
                Optional<Content<SpoonNode, RoledValues>> first,
                Content<SpoonNode, RoledValues> second,
                Content<SpoonNode, RoledValues> third) {
            super(first, second, third);
        }
    }
}
