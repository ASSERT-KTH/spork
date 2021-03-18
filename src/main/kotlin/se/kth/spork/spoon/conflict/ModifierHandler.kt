package se.kth.spork.spoon.conflict

import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.ModifierKind
import spoon.reflect.path.CtRole
import java.util.HashSet
import java.util.stream.Stream

import kotlin.Pair
import kotlin.Triple

/**
 * Conflict handler for modifiers. This handler can partially merge results.
 *
 * @author Simon Larsén
 */
class ModifierHandler : ContentConflictHandler {
    override val role: CtRole
        get() = CtRole.MODIFIER

    @Suppress("UNCHECKED_CAST")
    override fun handleConflict(
        baseVal: Any?,
        leftVal: Any,
        rightVal: Any,
        baseElem: CtElement?,
        leftElem: CtElement,
        rightElem: CtElement
    ): Pair<Any?, Boolean> {
        return mergeModifierKinds(
            (baseVal ?: setOf<ModifierKind>()) as Set<ModifierKind>,
            leftVal as Set<ModifierKind>,
            rightVal as Set<ModifierKind>
        )
    }

    companion object {
        /**
         * Separate modifiers into visibility (public, private, protected), keywords (static, final) and
         * all others.
         *
         * @param modifiers A stream of modifiers.
         * @return A triple with visibility in first, keywords in second and other in third.
         */
        private fun categorizeModifiers(modifiers: Stream<ModifierKind>): Triple<MutableSet<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>> {
            val visibility: MutableSet<ModifierKind> = HashSet()
            val keywords: MutableSet<ModifierKind> = HashSet()
            val other: MutableSet<ModifierKind> = HashSet()
            modifiers.forEach { mod: ModifierKind ->
                when (mod) {
                    ModifierKind.PRIVATE, ModifierKind.PUBLIC, ModifierKind.PROTECTED -> visibility.add(mod)
                    ModifierKind.ABSTRACT, ModifierKind.FINAL -> keywords.add(mod)
                    else -> other.add(mod)
                }
            }
            return Triple(visibility, keywords, other)
        }

        /**
         * Separate modifiers into visibility (public, private, protected), keywords (static, final) and
         * all others.
         *
         * @param modifiers A collection of modifiers.
         * @return A triple with visibility in first, keywords in second and other in third.
         */
        fun categorizeModifiers(modifiers: Collection<ModifierKind>?): Triple<MutableSet<ModifierKind>, Set<ModifierKind>, Set<ModifierKind>> {
            return categorizeModifiers(modifiers!!.stream())
        }

        /**
         * Extract the visibility modifier(s).
         *
         * @param modifiers A collection of modifiers.
         * @return A possibly empty set of visibility modifiers.
         */
        private fun getVisibility(modifiers: Collection<ModifierKind>?): MutableSet<ModifierKind> {
            return categorizeModifiers(modifiers).first
        }

        /**
         * Return a pair (conflict, mergedModifiers). If the conflict value is true, there is a conflict
         * in the visibility modifiers, and the merged value will always be the left one.
         */
        private fun mergeModifierKinds(
            base: Set<ModifierKind>,
            left: Set<ModifierKind>,
            right: Set<ModifierKind>
        ): Pair<Any?, Boolean> {
            val modifiers = base + left + right
            val baseVis = getVisibility(base)
            val leftVis = getVisibility(left)
            val rightVis = getVisibility(right)

            val categorizedMods = categorizeModifiers(modifiers)
            var visibility = categorizedMods.first
            val keywords = categorizedMods.second
            val other = categorizedMods.third
            if (visibility.size > 1) {
                visibility.removeIf { o: ModifierKind? -> base.contains(o) }
            }

            // visibility is the only place where we can have obvious addition conflicts
            // TODO further analyze conflicts among other modifiers (e.g. you can't combine static and volatile)
            val conflict = (
                visibility.size != 1 ||
                    (
                        leftVis != rightVis &&
                            leftVis != baseVis &&
                            rightVis != baseVis
                        )
                )
            if (conflict) {
                // use left version on conflict to follow the convention
                visibility = leftVis
            }

            // present in both left and right == ALL GOOD
            val isInLeftAndRight = { m: ModifierKind -> m in left && m in right }
            // respect deletions, if an element is present in only one of left and right,
            // and is present in base, then it has been deleted
            val isDeleted = { m: ModifierKind -> m in base && ((m in left) xor (m in right)) }

            val mods = (visibility + keywords + other).filter {
                mod ->
                isInLeftAndRight(mod) || !isDeleted(mod)
            }.toSet()
            return Pair(mods, conflict)
        }
    }
}
