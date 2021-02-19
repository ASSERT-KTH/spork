package se.kth.spork.spoon.conflict;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import se.kth.spork.util.Pair;
import se.kth.spork.util.Triple;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.path.CtRole;

/**
 * Conflict handler for modifiers. This handler can partially merge results.
 *
 * @author Simon Larsén
 */
public class ModifierHandler implements ContentConflictHandler {

    @Override
    public CtRole getRole() {
        return CtRole.MODIFIER;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Pair<Optional<Object>, Boolean> handleConflict(
            Optional<Object> baseVal,
            Object leftVal,
            Object rightVal,
            Optional<CtElement> baseElem,
            CtElement leftElem,
            CtElement rightElem) {
        return ModifierHandler.mergeModifierKinds(
                baseVal.map(o -> (Set<ModifierKind>) o),
                (Set<ModifierKind>) leftVal,
                (Set<ModifierKind>) rightVal);
    }

    /**
     * Separate modifiers into visibility (public, private, protected), keywords (static, final) and
     * all others.
     *
     * @param modifiers A stream of modifiers.
     * @return A triple with visibility in first, keywords in second and other in third.
     */
    private static Triple<Set<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>>
            categorizeModifiers(Stream<ModifierKind> modifiers) {
        Set<ModifierKind> visibility = new HashSet<>();
        Set<ModifierKind> keywords = new HashSet<>();
        Set<ModifierKind> other = new HashSet<>();

        modifiers.forEach(
                mod -> {
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
     * Separate modifiers into visibility (public, private, protected), keywords (static, final) and
     * all others.
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
    private static Set<ModifierKind> getVisibility(Collection<ModifierKind> modifiers) {
        return categorizeModifiers(modifiers).first;
    }

    /**
     * Return a pair (conflict, mergedModifiers). If the conflict value is true, there is a conflict
     * in the visibility modifiers, and the merged value will always be the left one.
     */
    private static Pair<Optional<Object>, Boolean> mergeModifierKinds(
            Optional<Set<ModifierKind>> base, Set<ModifierKind> left, Set<ModifierKind> right) {
        Set<ModifierKind> baseModifiers = base.orElseGet(HashSet::new);

        Stream<ModifierKind> modifiers = Stream.of(baseModifiers, left, right).flatMap(Set::stream);
        Triple<Set<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>> categorizedMods =
                categorizeModifiers(modifiers);

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
        // TODO further analyze conflicts among other modifiers (e.g. you can't combine static and
        // volatile)
        boolean conflict =
                visibility.size() != 1
                        || !leftVis.equals(rightVis)
                                && !leftVis.equals(baseVis)
                                && !rightVis.equals(baseVis);

        if (conflict) {
            // use left version on conflict to follow the convention
            visibility = leftVis;
        }

        Set<ModifierKind> mods =
                Stream.of(visibility, keywords, other)
                        .flatMap(Set::stream)
                        .filter(
                                mod ->
                                        // present in both left and right == ALL GOOD
                                        left.contains(mod) && right.contains(mod)
                                                ||
                                                // respect deletions, if an element is present in
                                                // only one of left and right, and is
                                                // present in base, then it has been deleted
                                                (left.contains(mod) ^ right.contains(mod))
                                                        && !baseModifiers.contains(mod))
                        .collect(Collectors.toSet());

        return Pair.of(Optional.of(mods), conflict);
    }
}
