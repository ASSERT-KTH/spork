package se.kth.spork.spoon.pcsinterpreter

import se.kth.spork.base3dm.ChangeSet
import se.kth.spork.base3dm.Content
import se.kth.spork.base3dm.ListNode
import se.kth.spork.base3dm.Pcs
import se.kth.spork.base3dm.Revision
import se.kth.spork.exception.ConflictException
import se.kth.spork.spoon.conflict.ConflictType
import se.kth.spork.spoon.conflict.StructuralConflict
import se.kth.spork.spoon.conflict.StructuralConflict.Companion.isPredecessorConflict
import se.kth.spork.spoon.conflict.StructuralConflict.Companion.isSuccessorConflict
import se.kth.spork.spoon.conflict.StructuralConflictHandler
import se.kth.spork.spoon.matching.SpoonMapping
import se.kth.spork.spoon.wrappers.NodeFactory.virtualRoot
import se.kth.spork.spoon.wrappers.RoledValues
import se.kth.spork.spoon.wrappers.SpoonNode
import se.kth.spork.util.LazyLogger
import se.kth.spork.util.lineBasedMerge
import java.lang.NullPointerException
import java.util.stream.Collectors

/**
 * Class for building a [SporkTree] from a merged [ChangeSet].
 *
 * @author Simon Larsén
 */
internal class SporkTreeBuilder(
    delta: ChangeSet<SpoonNode, RoledValues>,
    baseLeft: SpoonMapping,
    baseRight: SpoonMapping,
    conflictHandlers: List<StructuralConflictHandler>?
) {
    private val rootToChildren: Map<SpoonNode, Map<SpoonNode, Pcs<SpoonNode>>>
    private val structuralConflicts: Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>>
    private val contents: Map<SpoonNode, Set<Content<SpoonNode, RoledValues>>>
    private var numStructuralConflicts: Int

    // keeps track of which nodes have been added to the tree all ready
    // if any node is added twice, there's an unresolved move conflict
    private val usedNodes: MutableSet<SpoonNode?>

    // keeps track of all structural inconsistencies that are used
    // if any have not been used when the tree has been built, there's something wrong
    private val remainingInconsistencies: MutableSet<Pcs<SpoonNode>>
    private val baseLeft: SpoonMapping
    private val baseRight: SpoonMapping
    private val conflictHandlers: List<StructuralConflictHandler>

    /** Try to resolve a structural conflict automatically.  */
    private fun tryResolveConflict(
        leftNodes: List<SpoonNode>,
        rightNodes: List<SpoonNode>
    ): List<SpoonNode>? {
        // we can currently only resolve conflict lists for insert/insert conflicts
        // TODO Expand conflict handling to deal with more than just insert/insert
        val conflictType = ConflictType.INSERT_INSERT
        return conflictHandlers.mapNotNull { handler: StructuralConflictHandler ->
            handler.tryResolveConflict(
                leftNodes, rightNodes, conflictType
            )
        }.firstOrNull()
    }

    /** @return The amount of structural conflicts.
     */
    fun numStructuralConflicts(): Int {
        return numStructuralConflicts
    }

    fun buildTree(): SporkTree {
        return build(virtualRoot)
    }

    /**
     * Build a subtree of the [ChangeSet] contained in this builder. The [SporkTree]
     * that's returned contains all information required to build the final Spoon tree, including
     * structural conflict information.
     *
     * @param currentRoot The node from which to build the subtree.
     * @return A [SporkTree] rooted in the provided root node.
     */
    fun build(currentRoot: SpoonNode): SporkTree {
        val children: Map<SpoonNode, Pcs<SpoonNode>>? = rootToChildren[currentRoot]
        val currentContent = contents[currentRoot] ?: emptySet()
        var tree = SporkTree(currentRoot, currentContent)
        if (children == null) // leaf node
            return tree
        try {
            build(currentRoot.startOfChildList, tree, children)
            for (inconsistent in remainingInconsistencies) {
                if (inconsistent.root == tree.node) {
                    throw ConflictException("Missed conflict: $inconsistent")
                }
            }
        } catch (e: NullPointerException) {
            // could not resolve the child list
            // TODO improve design, should not have to catch exceptions like this
            LOGGER.warn {
                (
                    "Failed to resolve child list of " +
                        currentRoot.element.shortRepresentation +
                        ". Falling back to line-based merge of this element."
                    )
            }
            val conflict = approximateConflict(currentRoot)
            tree = SporkTree(currentRoot, currentContent, conflict)
            tree.revisions = setOf(Revision.BASE, Revision.LEFT, Revision.RIGHT)
        } catch (e: ConflictException) {
            LOGGER.warn {
                (
                    "Failed to resolve child list of " +
                        currentRoot.element.shortRepresentation +
                        ". Falling back to line-based merge of this element."
                    )
            }
            val conflict = approximateConflict(currentRoot)
            tree = SporkTree(currentRoot, currentContent, conflict)
            tree.revisions = setOf(Revision.BASE, Revision.LEFT, Revision.RIGHT)
        }
        return tree
    }

    private fun build(start: SpoonNode, tree: SporkTree, children: Map<SpoonNode, Pcs<SpoonNode>>?) {
        if (children == null) // leaf node
            return
        var next: SpoonNode? = start
        while (true) {
            val nextPcs = children[next]!!
            tree.addRevision(nextPcs.revision)
            next = nextPcs.successor
            if (next.isListEdge) {
                // can still have a conflict at the end of the child list
                getSuccessorConflict(nextPcs)?.apply {
                    traverseConflict(nextPcs, this, children, tree)
                }
                break
            }
            if (next.isVirtual && !next.isListEdge) {
                build(next.startOfChildList, tree, rootToChildren[next])
            } else {
                val successorConflict = getSuccessorConflict(nextPcs)
                if (successorConflict != null) {
                    next = traverseConflict(nextPcs, successorConflict, children, tree)
                } else {
                    addChild(tree, build(next))
                }

                if (next.isEndOfList) {
                    break
                }
            }
        }
    }

    private fun getSuccessorConflict(pcs: Pcs<SpoonNode>): Pcs<SpoonNode>? =
        structuralConflicts[pcs]?.firstOrNull {
            isSuccessorConflict(
                pcs, it
            )
        }

    /**
     * When a conflict in the child list of a node is not possible to resolve, we approximate the
     * conflict by finding the node's matches in the left and right revisions and have them make up
     * the conflict instead. This is a rough estimation, and if they nodes have large child lists it
     * will result in very large conflicts.
     *
     * @param node A node for which the child list could not be constructed.
     * @return An approximated conflict between the left and right matches of the node.
     */
    private fun approximateConflict(node: SpoonNode?): StructuralConflict {
        val base: SpoonNode?
        val left: SpoonNode?
        val right: SpoonNode?
        when (node!!.revision) {
            Revision.LEFT -> {
                left = node
                base = baseLeft.getSrc(left)
                right = baseRight.getDst(base!!)
            }
            Revision.RIGHT -> {
                right = node
                base = baseRight.getSrc(right)
                left = baseLeft.getDst(base!!)
            }
            Revision.BASE -> {
                base = node
                left = baseLeft.getDst(node)
                right = baseRight.getDst(node)
            }
        }
        val (first, second) = lineBasedMerge(
            base.element, left!!.element, right!!.element
        )
        numStructuralConflicts += second
        return StructuralConflict(
            base.element, left.element, right.element, first
        )
    }

    private fun traverseConflict(
        nextPcs: Pcs<SpoonNode>,
        conflicting: Pcs<SpoonNode>,
        children: Map<SpoonNode, Pcs<SpoonNode>>,
        tree: SporkTree
    ): SpoonNode {
        remainingInconsistencies.remove(nextPcs)
        remainingInconsistencies.remove(conflicting)
        listOf(Revision.LEFT, Revision.RIGHT).forEach(tree::addRevision)
        val leftPcs = if (nextPcs.revision === Revision.LEFT) nextPcs else conflicting
        val rightPcs = if (leftPcs === nextPcs) conflicting else nextPcs
        val leftNodes = extractConflictList(leftPcs, children)
        val rightNodes = extractConflictList(rightPcs, children)
        val resolved = tryResolveConflict(leftNodes, rightNodes)

        // if nextPcs happens to be the final PCS of a child list, next may be a virtual node
        val next: SpoonNode = if (leftNodes.isEmpty()) rightNodes[rightNodes.size - 1] else leftNodes[leftNodes.size - 1]
        if (resolved != null) {
            resolved.forEach { child: SpoonNode -> addChild(tree, build(child)) }
        } else {
            numStructuralConflicts++
            val conflict = StructuralConflict(
                leftNodes.stream()
                    .map(SpoonNode::element)
                    .collect(Collectors.toList()),
                rightNodes.stream()
                    .map(SpoonNode::element)
                    .collect(Collectors.toList())
            )
            // next is used as a dummy node here, so it should not be added to usedNodes
            tree.addChild(SporkTree(next, contents[next]!!, conflict))
        }
        // by convention, build left tree
        return next
    }

    private fun addChild(tree: SporkTree, child: SporkTree) {
        if (usedNodes.contains(child.node)) {
            // if this happens, then there is a duplicate node in the tree, indicating a move
            // conflict
            throw ConflictException("Move conflict detected")
        }
        tree.addChild(child)
        usedNodes.add(child.node)
    }

    /**
     * Scan ahead in the PCS structure to resolve the conflicting children. The conflict must end
     * with a predecessor conflict, or an exception is thrown.
     */
    private fun extractConflictList(
        pcs: Pcs<SpoonNode>,
        siblings: Map<SpoonNode, Pcs<SpoonNode>>
    ): List<SpoonNode> {
        var currentPcs = pcs
        val nodes: MutableList<SpoonNode> = mutableListOf()
        while (true) {
            val conflicts = structuralConflicts[currentPcs]
            if (conflicts != null && !conflicts.isEmpty()) {
                val finalPcs = currentPcs
                val predConflict = conflicts.stream()
                    .filter {
                        isPredecessorConflict(
                            finalPcs, it
                        )
                    }
                    .findFirst()
                if (predConflict.isPresent) {
                    remainingInconsistencies.remove(predConflict.get())
                    return nodes
                }
            }
            val nextNode = currentPcs.successor
            if (nextNode.isEndOfList) throw ConflictException(
                "Reached the end of the child list without finding a predecessor conflict"
            )
            nodes.add(nextNode)
            currentPcs = siblings[nextNode]!!
        }
    }

    companion object {
        private val LOGGER = LazyLogger(SporkTreeBuilder::class.java)
        private fun <T : ListNode> buildRootToChildren(
            pcses: Set<Pcs<T>>
        ): Map<T, MutableMap<T, Pcs<T>>> {
            val rootToChildren: MutableMap<T, MutableMap<T, Pcs<T>>> = HashMap()
            for (pcs in pcses) {
                val children = rootToChildren.getOrDefault(pcs.root, HashMap())
                if (children.isEmpty()) rootToChildren[pcs.root] = children
                children[pcs.predecessor] = pcs
            }
            return rootToChildren
        }
    }

    init {
        rootToChildren = buildRootToChildren(delta.pcsSet)
        this.baseLeft = baseLeft
        this.baseRight = baseRight
        this.conflictHandlers = ArrayList(conflictHandlers)
        structuralConflicts = delta.structuralConflicts
        contents = delta.contents
        numStructuralConflicts = 0
        usedNodes = HashSet()
        remainingInconsistencies = HashSet()
        remainingInconsistencies.addAll(structuralConflicts.values.flatten())
    }
}
