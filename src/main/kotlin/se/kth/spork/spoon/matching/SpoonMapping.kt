package se.kth.spork.spoon.matching

import com.github.gumtreediff.matchers.MappingStore
import com.github.gumtreediff.tree.ITree
import com.github.gumtreediff.utils.Pair
import gumtree.spoon.builder.CtWrapper
import gumtree.spoon.builder.SpoonGumTreeBuilder
import se.kth.spork.spoon.wrappers.NodeFactory
import se.kth.spork.spoon.wrappers.SpoonNode
import se.kth.spork.util.GumTreeSpoonAstDiff
import spoon.reflect.declaration.CtAnnotation
import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.CtTypeInformation
import spoon.reflect.path.CtRole
import java.util.*
import java.util.stream.Collectors

/**
 * A class for storing matches between tree nodes in two Spoon trees. Inspired by the MappingStore class from GumTree.
 *
 *
 * See [
 * MappingStore.java
](https://github.com/GumTreeDiff/gumtree/blob/f20565b6261fe3465cd1b3e0914028d5e87699b2/core/src/main/java/com/github/gumtreediff/matchers/MappingStore.java#L1-L151) *  in GumTree for comparison.
 *
 *
 * It is my opinion that this file is sufficiently distinct from GumTree's MappingStore that the former does not count
 * as a derivative of the latter, and the similar functionality is trivial. Therefore, I do not think that the
 * LGPL license of the GumTree project needs to be applied to Spork.
 *
 * @author Simon Larsén
 */
class SpoonMapping private constructor() {
    private val srcs: MutableMap<SpoonNode, SpoonNode?>
    private val dsts: MutableMap<SpoonNode, SpoonNode?>
    private fun asList(): List<Pair<CtElement, CtElement>> {
        return srcs.values.stream()
                .map { dst: SpoonNode? -> Pair(getSrc(dst)!!.element, dst!!.element) }
                .collect(Collectors.toList())
    }

    /**
     * Infer additional node matches. It is done by iterating over all pairs of matched nodes, and for each pair,
     * descending down into the tree incrementally and matching nodes that gumtree-spoon-ast-diff is known to
     * ignore. See [TreeScanner](https://github.com/SpoonLabs/gumtree-spoon-ast-diff/blob/dae908192bee7773b38d149baff831ee616ec524/src/main/java/gumtree/spoon/builder/TreeScanner.java#L71-L84)
     * to see how nodes are ignored in gumtree-spoon-ast-diff. The process is repeated for each pair of newly matched
     * nodes, until no new matches can be found.
     *
     * @param matches Pairs of matched nodes, as computed by GumTree/gumtree-spoon-ast-diff.
     */
    private fun inferAdditionalMappings(matches: List<Pair<CtElement, CtElement>>) {
        var matches = matches
        while (!matches.isEmpty()) {
            val newMatches: MutableList<Pair<CtElement, CtElement>> = ArrayList()
            for (match in matches) {
                val src = match.first
                val dst = match.second
                newMatches.addAll(inferAdditionalMappings(src, dst))
            }
            matches = newMatches
        }
    }

    private fun inferAdditionalMappings(src: CtElement, dst: CtElement): List<Pair<CtElement, CtElement>> {
        val srcChildren = src.directChildren
        val dstChildren = dst.directChildren
        val newMatches: MutableList<Pair<CtElement, CtElement>> = ArrayList()
        var srcIdx = 0
        var dstIdx = 0
        while (srcIdx < srcChildren.size && dstIdx < dstChildren.size) {
            val srcChild = srcChildren[srcIdx]
            val dstChild = dstChildren[dstIdx]
            if (hasSrc(srcChild) || !GumTreeSpoonAstDiff.isToIgnore(srcChild)) {
                srcIdx++
            } else if (hasDst(dstChild) || !GumTreeSpoonAstDiff.isToIgnore(dstChild)) {
                dstIdx++
            } else {
                if (!ignoreMapping(srcChild, dstChild)) {
                    put(srcChild, dstChild)
                    newMatches.add(Pair(srcChild, dstChild))
                }
                srcIdx++
                dstIdx++
            }
        }
        return newMatches
    }

    fun hasSrc(src: SpoonNode?): Boolean {
        return srcs.containsKey(src)
    }

    fun hasDst(dst: SpoonNode?): Boolean {
        return dsts.containsKey(dst)
    }

    fun hasSrc(src: CtElement?): Boolean {
        return hasSrc(NodeFactory.wrap(src))
    }

    fun hasDst(dst: CtElement?): Boolean {
        return hasDst(NodeFactory.wrap(dst))
    }

    fun getDst(src: SpoonNode?): SpoonNode? {
        return srcs[src]
    }

    fun getDst(src: CtElement?): CtElement {
        return getDst(NodeFactory.wrap(src))!!.element
    }

    fun getSrc(dst: SpoonNode?): SpoonNode? {
        return dsts[dst]
    }

    fun getSrc(dst: CtElement?): CtElement {
        return getSrc(NodeFactory.wrap(dst))!!.element
    }

    fun remove(element: SpoonNode?) {
        val removedDst = srcs.remove(element)
        val removedSrc = dsts.remove(element)
        dsts.remove(removedDst)
        srcs.remove(removedSrc)
    }

    fun put(src: CtElement?, dst: CtElement?) {
        put(NodeFactory.wrap(src), NodeFactory.wrap(dst))
    }

    fun put(src: SpoonNode, dst: SpoonNode) {
        srcs[src] = dst
        dsts[dst] = src
    }

    private fun formatEntry(entry: Map.Entry<SpoonNode, SpoonNode?>): String {
        return "(" + entry.key + ", " + entry.value + ")"
    }

    override fun toString(): String {
        return "SpoonMappingStore{" +
                "srcs=" + srcs.entries.stream().map { entry: Map.Entry<SpoonNode, SpoonNode?> -> formatEntry(entry) }.collect(Collectors.toList()) +
                ", dsts=" + dsts.entries.stream().map { entry: Map.Entry<SpoonNode, SpoonNode?> -> formatEntry(entry) }.collect(Collectors.toList()) +
                '}'
    }

    companion object {
        /**
         * Create a Spoon mapping from a GumTree mapping. Every GumTree node must have a "spoon_object" metadata object that
         * refers back to a Spoon node. As this mapping does not cover the whole Spoon tree, additional mappings are
         * inferred.
         *
         *
         * TODO verify that the mapping inference is actually correct
         *
         * @param gumtreeMapping A GumTree mapping in which each mapped node has a "spoon_object" metadata object.
         * @return A SpoonMapping corresponding to the passed GumTree mapping.
         */
        fun fromGumTreeMapping(gumtreeMapping: MappingStore): SpoonMapping {
            val mapping = SpoonMapping()
            for (m in gumtreeMapping.asSet()) {
                val spoonSrc = getSpoonNode(m.first)
                val spoonDst = getSpoonNode(m.second)
                if (spoonSrc == null || spoonDst == null) {
                    // at least one was non-null
                    check(!(spoonSrc !== spoonDst))
                    check(m.first.type == -1) { // -1 is the type given to root node in SpoonGumTreeBuilder
                        ("non-root node " + m.first.toShortString()
                                + " had no mapped Spoon object")
                    }
                } else if (!ignoreMapping(spoonSrc, spoonDst)) {
                    mapping.put(spoonSrc, spoonDst)
                }
            }
            mapping.inferAdditionalMappings(mapping.asList())
            return mapping
        }

        /**
         * Sometimes, we want to ignore a mapping that GumTree produces, as it causes trouble for the merge algorithm.
         */
        private fun ignoreMapping(src: CtElement, dst: CtElement): Boolean {
            if (src.javaClass != dst.javaClass) {
                // It is important to only map nodes of the exact same type, as 3DM has no notion of "correct"
                // parent-child relationships. Mapping e.g. an array type reference to a non-array type reference
                // may cause the resulting merge to try to treat either as the other, which does not work out.
                return true
            } else if (isAnnotationValue(src) != isAnnotationValue(dst)) {
                // If one element is an annotation value, but the other is not, mapping them will cause issues resolving
                // the key of the value. This is a problem related to how annotations are represented in Spoon, namely
                // that the keys in the annotation map aren't proper nodes.
                return true
            } else if (isPrimitiveType(src) != isPrimitiveType(dst)) {
                return true
            } else if (src is CtWrapper<*> || dst is CtWrapper<*>) {
                // the CtWrapper elements do not represent real Spoon nodes, and so are just noise
                return true
            }
            return false
        }

        private fun isPrimitiveType(elem: CtElement): Boolean {
            return if (elem is CtTypeInformation) {
                (elem as CtTypeInformation).isPrimitive
            } else false
        }

        private fun isAnnotationValue(elem: CtElement): Boolean {
            return elem.parent is CtAnnotation<*> && elem.roleInParent == CtRole.VALUE
        }

        private fun getSpoonNode(gumtreeNode: ITree): CtElement? {
            return gumtreeNode.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT) as CtElement?
        }
    }

    init {
        srcs = HashMap()
        dsts = HashMap()
    }
}