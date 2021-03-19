package se.kth.spork.spoon.pcsinterpreter

import se.kth.spork.base3dm.REV
import se.kth.spork.base3dm.Revision
import se.kth.spork.spoon.Parser.setSporkEnvironment
import se.kth.spork.spoon.conflict.ContentConflict
import se.kth.spork.spoon.conflict.ContentConflictHandler
import se.kth.spork.spoon.conflict.ContentMerger
import se.kth.spork.spoon.conflict.StructuralConflict
import se.kth.spork.spoon.matching.SpoonMapping
import se.kth.spork.spoon.wrappers.NodeFactory
import se.kth.spork.spoon.wrappers.NodeFactory.forceWrap
import se.kth.spork.spoon.wrappers.NodeFactory.virtualRoot
import se.kth.spork.spoon.wrappers.NodeFactory.wrap
import se.kth.spork.spoon.wrappers.SpoonNode
import spoon.Launcher
import spoon.compiler.Environment
import spoon.reflect.CtModelImpl.CtRootPackage
import spoon.reflect.code.CtExpression
import spoon.reflect.cu.SourcePosition
import spoon.reflect.declaration.CtAnnotation
import spoon.reflect.declaration.CtElement
import spoon.reflect.factory.Factory
import spoon.reflect.factory.ModuleFactory.CtUnnamedModule
import spoon.reflect.path.CtRole
import spoon.reflect.reference.CtParameterReference
import spoon.reflect.reference.CtTypeReference
import java.lang.IllegalStateException
import java.util.TreeMap
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * Class for building a Spoon tree (i.e. a CtElement) from a [SporkTree].
 *
 * @param baseLeft The base-to-left tree matching.
 * @param baseRight The base-to-right tree matching.
 * @param oldEnv Any environment used in the merge. It's needed to copy some values.
 * @param contentConflictHandlers A list of conflict handlers.
*/
class SpoonTreeBuilder internal constructor(
    private val baseLeft: SpoonMapping,
    private val baseRight: SpoonMapping,
    oldEnv: Environment,
    contentConflictHandlers: List<ContentConflictHandler>
) {
    private var numContentConflicts = 0
    private val factory: Factory = Launcher().factory

    // A mapping from the original node to its copy in the merged tree
    private val nodes: MutableMap<SpoonNode, SpoonNode> = mutableMapOf()
    private val contentMerger: ContentMerger = ContentMerger(contentConflictHandlers)

    /**
     * Create a shallow copy of a tree.
     *
     * @param tree A tree to copy.
     * @return A shallow copy of the input tree.
     */
    private fun shallowCopyTree(tree: CtElement, factory: Factory): CtElement {
        if (tree is CtUnnamedModule) {
            return factory.Module().unnamedModule
        } else if (tree is CtRootPackage) {
            return factory.Package().rootPackage
        }

        // FIXME This is super inefficient, cloning the whole tree just to delete all its children
        val treeCopy = tree.clone()
        for (child in treeCopy.directChildren) {
            child.delete()
        }
        treeCopy.factory = factory
        return treeCopy
    }

    /**
     * Build the children of the provided root.
     *
     * @param root The root of a [SporkTree]
     * @return The last Spoon tree in the built child list. This may be null!
     */
    fun build(root: SporkTree): CtElement? {
        var lastChild: CtElement? = null
        for (child in root.children) {
            val conflict = child.structuralConflict
            lastChild = conflict?.let {
                visitConflicting(root.node, it)
            } ?: visit(root, child)

            if (root.node === virtualRoot ||
                !child.isSingleRevisionSubtree
            ) build(child)
        }
        return lastChild
    }

    /** @return The amount of conflicts.
     */
    fun numContentConflicts(): Int {
        return numContentConflicts
    }

    /**
     * Visit a node an merge it. Note that both the node being visited, and its parent, are the
     * original nodes from the input trees.
     *
     * @param sporkParent A wrapper around the current node's parent.
     * @param sporkChild A wrapper around the current node being visited.
     */
    private fun visit(sporkParent: SporkTree, sporkChild: SporkTree): CtElement {
        val origRootNode = sporkParent.node
        val origTreeNode = sporkChild.node
        val originalTree = origTreeNode.element
        val mergeParent = if (origRootNode === virtualRoot) null else nodes[origRootNode]!!.element
        val mergeTree: CtElement
        if (sporkChild.isSingleRevisionSubtree) {
            mergeTree = originalTree.clone()
            mergeTree.putMetadata<CtElement>(SINGLE_REVISION_KEY, sporkChild.singleRevision)
        } else {
            val mergedContent = contentMerger.mergedContent(sporkChild.content)
            mergeTree = shallowCopyTree(originalTree, factory)
            mergedContent
                .first?.forEach { roledValue ->
                mergeTree.setValueByRole<CtElement, Any?>(roledValue.role, roledValue.value)
            }
            if (mergedContent.second.isNotEmpty()) {
                // at least one conflict was not resolved
                mergeTree.putMetadata<CtElement>(ContentConflict.METADATA_KEY, mergedContent.second)
                numContentConflicts += mergedContent.second.size
            }
        }

        // adjust metadata for the merge tree
        val metadata: MutableMap<String, Any> = HashMap(mergeTree.allMetadata)
        metadata.remove(NodeFactory.WRAPPER_METADATA)
        metadata[ORIGINAL_NODE_KEY] = originalTree
        mergeTree.setAllMetadata<CtElement>(metadata)

        if (mergeParent != null) {
            val mergeTreeRole = resolveRole(origTreeNode)
            val inserted = withSiblings(originalTree, mergeParent, mergeTree, mergeTreeRole)
            if (mergeTreeRole == CtRole.TYPE_MEMBER || mergeTreeRole == CtRole.COMMENT) {
                unsetSourcePosition(mergeTree)
            }
            if (isVarKeyword(mergeTree) &&
                mergeParent is CtParameterReference<*> &&
                mergeTreeRole == CtRole.TYPE
            ) {
                // we skip this case, because  for some reason, when it comes to parameter references, Spoon sets
                // the type to null if it's actually "var"
            } else {
                mergeParent.setValueByRole<CtElement, Any>(mergeTreeRole, inserted)
            }
        }
        val mergeNode: SpoonNode = if (mergeParent != null) {
            // NOTE: Super important that the parent of the merge tree is set no matter what, as
            // wrapping a spoon CtElement
            // in a SpoonNode requires access to its parent.
            mergeTree.setParent(mergeParent)
            wrap(mergeTree)
        } else {
            // if the merge tree has no parent, then its parent is the virtual root
            forceWrap(mergeTree, virtualRoot)
        }
        nodes[origTreeNode] = mergeNode
        return mergeTree
    }

    /**
     * Visit the root nodes of a conflict. Note that the children of these nodes are not visited by
     * this method.
     *
     * @param parent The parent node of the conflict.
     * @param conflict The current structural conflict.
     */
    private fun visitConflicting(parent: SpoonNode, conflict: StructuralConflict): CtElement {
        val dummy = if (conflict.left.isNotEmpty()) conflict.left[0] else conflict.right[0]
        val mergeParent = nodes[parent]!!.element
        dummy.putMetadata<CtElement>(StructuralConflict.METADATA_KEY, conflict)
        val dummyNode = wrap(dummy)
        val role = resolveRole(dummyNode)
        val inserted = withSiblings(dummy, mergeParent, dummy, role)
        dummy.delete()
        mergeParent.setValueByRole<CtElement, Any>(role, inserted)
        return dummy
    }

    private fun isVarKeyword(mergeTree: CtElement): Boolean {
        return (
            mergeTree is CtTypeReference<*> &&
                mergeTree.simpleName == "var"
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun withSiblings(
        originalTree: CtElement,
        mergeParent: CtElement,
        mergeTree: CtElement,
        mergeTreeRole: CtRole
    ): Any {
        val siblings = mergeParent.getValueByRole<Any>(mergeTreeRole)
        val inserted: Any = when (siblings) {
            is Collection<*> -> {
                val mutableCurrent: MutableCollection<CtElement> = when (siblings) {
                    is Set<*> -> {
                        (siblings as Collection<CtElement>).toHashSet()
                    }
                    is List<*> -> {
                        (siblings as Collection<CtElement>).toMutableList()
                    }
                    else -> {
                        throw IllegalStateException("unexpected value by role: " + siblings.javaClass)
                    }
                }
                mutableCurrent.add(mergeTree)
                mutableCurrent
            }
            is Map<*, *> -> {
                resolveAnnotationMap(mergeTree, siblings, originalTree)
            }
            else -> {
                mergeTree
            }
        }
        return inserted
    }

    /**
     * Resolving the role of a node in the merged tree is tricky, but with a few assumptions it can
     * be done quickly.
     *
     *
     * First of all, it is fairly safe to assume that the node can have at most two roles. Assume
     * for a second that a node could have three roles. This means that the node has been modified
     * inconsistently in the left and right revisions, and by the definition of 3DM merge there will
     * have been a structural conflict already.
     *
     *
     * Second, it is also safe to assume that if the role differs between base and either left or
     * right, the role in base should be discarded. This is safe to assume as all edits of left and
     * right will appear in the merged tree.
     *
     *
     * Thus, given that the base revision's role is resolved, it will always be possible to
     * resolve the unique role that should be applied next. This also means that a problem occurs
     * when a left-to-right mapping is used, as there may then be nodes that only match between left
     * and right, and no clear way of determining which of the two roles should be used, if they
     * differ. I have yet to figure out how to resolve that.
     *
     * @param wrapper A wrapped Spoon node.
     * @return The resolved role of this node in the merged tree.
     */
    private fun resolveRole(wrapper: SpoonNode?): CtRole {
        val matches: MutableList<CtRole> = ArrayList()
        val tree = wrapper!!.element
        matches.add(wrapper.element.roleInParent)
        var base: SpoonNode? = null
        when (tree.getMetadata(REV) as Revision) {
            Revision.BASE -> {
                base = wrapper
                val left = baseLeft.getDst(wrapper)
                val right = baseRight.getDst(wrapper)
                if (left != null) matches.add(left.element.roleInParent)
                if (right != null) matches.add(right.element.roleInParent)
            }
            Revision.RIGHT -> {
                val match = baseRight.getSrc(wrapper)
                if (match != null) {
                    matches.add(match.element.roleInParent)
                    base = match
                }
            }
            Revision.LEFT -> {
                val match = baseLeft.getSrc(wrapper)
                if (match != null) {
                    matches.add(match.element.roleInParent)
                    base = match
                }
            }
        }
        if (base != null) {
            val baseRole = base.element.roleInParent
            matches.removeIf { w: CtRole -> w == baseRole }
            if (matches.isEmpty()) {
                return baseRole
            }
        }
        assert(matches.size == 1)
        return matches[0]
    }

    /**
     * Resolve they key/value mapping that forms the "body" of an annotation, assuming that
     * mergeTree is a new value to be inserted (i.e. mergeTree's parent is an annotation).
     *
     *
     * This is a bit fiddly, as there are many ways in which the key/value map can be expressed
     * in source code. See [the Oracle docs](https://docs.oracle.com/javase/tutorial/java/annotations/basics.html)
     * for more info on annotations.
     *
     * Note: This method mutates none of the input.
     *
     * @param mergeTree The tree node currently being merged, to be inserted as a value among
     * siblings.
     * @param siblings A potentially empty map of annotation keys->values currently in the merge
     * tree's parent's children, i.e. the siblings of the current mergeTree.
     * @param originalTree The tree from which mergeTree was copied.
     * @return A map representing the key/value pairs of an annotation, wich mergeTree inserted
     * among its siblings.
     */
    private fun resolveAnnotationMap(
        mergeTree: CtElement,
        siblings: Map<*, *>,
        originalTree: CtElement
    ): Map<*, *> {
        val mutableCurrent: MutableMap<Any, Any> = TreeMap(siblings)
        val annotation = originalTree.parent as CtAnnotation<*>
        val originalEntry: Map.Entry<String, CtExpression<*>>? =
            annotation.values.entries.firstOrNull { entry -> entry.value === originalTree }
        requireNotNull(originalEntry) { "Internal error: unable to find key for annotation value $mergeTree" }
        mutableCurrent[originalEntry.key] = mergeTree
        return mutableCurrent
    }

    companion object {
        const val ORIGINAL_NODE_KEY = "spork_original_node"
        const val SINGLE_REVISION_KEY = "spork_single_revision"

        // the position key is used to put the original source position of an element as metadata
        // this is necessary e.g. for comments as their original source position may cause them not to
        // be printed
        // in a merged tree
        const val POSITION_KEY = "spork_position"

        /**
         * Some elements are inserted into the Spoon tree based on their position. This applies for
         * example to type members, as Spoon will try to find the appropriate position for them based on
         * position.
         *
         *
         * Comments that come from a different source file than the node they are attached to are
         * also unlikely to actually get printed, as the position relative to the associated node is
         * taken into account by the pretty-printer. Setting the position to [ ][SourcePosition.NOPOSITION] causes all comments to be printed before the associated node, but
         * at least they get printed!
         *
         *
         * The reason for this can be found in [ ][spoon.reflect.visitor.ElementPrinterHelper.getComments].
         *
         *
         * If the position is all ready [SourcePosition.NOPOSITION], then do nothing.
         */
        private fun unsetSourcePosition(element: CtElement) {
            if (!element.position.isValidPosition) return
            element.putMetadata<CtElement>(POSITION_KEY, element.position)
            element.setPosition<CtElement>(SourcePosition.NOPOSITION)
        }
    }

    init {
        setSporkEnvironment(
            factory.environment, oldEnv.tabulationSize, oldEnv.isUsingTabulations
        )
    }
}
