package se.kth.spork.spoon.matching

import se.kth.spork.base3dm.Revision
import se.kth.spork.spoon.wrappers.NodeFactory
import se.kth.spork.spoon.wrappers.SpoonNode
import spoon.reflect.declaration.CtElement
import spoon.reflect.visitor.CtScanner
import java.util.HashMap

/**
 * Create the class representatives mapping. The class representatives for the different revisions are defined as:
 *
 * 1. A node NB in base is its own class representative.
 * 2. The class representative of a node NL in left is NB if there exists a tree matching NL -> NB in the baseLeft
 * matching. Otherwise it is NL.
 * 3. The class representative of a node NR in right is NB if there exists a tree matching NR -> NB in the baseRight
 * matching. If that is not the case, the class representative may be NL if there exists a tree matching
 * NL -> NR. The latter is referred to as an augmentation, and is done conservatively to avoid spurious
 * mappings between left and right revisions. See [ClassRepresentativeAugmenter] for more info.
 *
 * Put briefly, base nodes are always mapped to themselves, nodes in left are mapped to base nodes if they are
 * matched, and nodes in right are mapped to base nodes or left nodes if they are matched, with base matchings
 * having priority.
 *
 * @param base      The base revision.
 * @param left      The left revision.
 * @param right     The right revision.
 * @param baseLeft  A matching from base to left.
 * @param baseRight A matching from base to right.
 * @param leftRight A matching from left to right.
 * @return The class representatives map.
 */
fun createClassRepresentativesMapping(
    base: CtElement,
    left: CtElement,
    right: CtElement,
    baseLeft: SpoonMapping,
    baseRight: SpoonMapping,
    leftRight: SpoonMapping
): Map<SpoonNode, SpoonNode> {
    val classRepMap = initializeClassRepresentatives(base)
    mapToClassRepresentatives(left, baseLeft, classRepMap, Revision.LEFT)
    mapToClassRepresentatives(right, baseRight, classRepMap, Revision.RIGHT)
    ClassRepresentativeAugmenter(classRepMap, leftRight).scan(left)
    return classRepMap
}

/**
 * Initialize the class representatives map by mapping each element in base to itself.
 *
 * @param base The base revision of the trees to be merged.
 * @return An initialized class representatives map.
 */
private fun initializeClassRepresentatives(base: CtElement): MutableMap<SpoonNode, SpoonNode> {
    val classRepMap: MutableMap<SpoonNode, SpoonNode> = HashMap()
    base.descendantIterator().forEach {
        NodeFactory.setRevisionIfUnset(it, Revision.BASE)
        val wrapped = NodeFactory.wrap(it)
        mapNodes(wrapped, wrapped, classRepMap)
    }

    // and finally the virtual root
    mapNodes(NodeFactory.virtualRoot, NodeFactory.virtualRoot, classRepMap)
    return classRepMap
}

/**
 * Map the nodes of a tree revision (left or right) to their corresponding class representatives. For example, if a
 * node NL in the left revision is matched to a node NB in the base revision, then the mapping NL -> NB is entered
 * into the class representatives map.
 *
 * This method also attaches the tree's revision to each node in the tree.
 *
 * TODO move attaching of the tree revision somewhere else, it's super obtuse to have here.
 *
 * @param tree        A revision of the trees to be merged (left or right).
 * @param mappings    A tree matching from the base revision to the provided tree.
 * @param classRepMap The class representatives map.
 * @param rev         The provided tree's revision.
 */
private fun mapToClassRepresentatives(tree: CtElement, mappings: SpoonMapping, classRepMap: MutableMap<SpoonNode, SpoonNode>, rev: Revision) {
    val descIt = tree.descendantIterator()
    while (descIt.hasNext()) {
        val t = descIt.next()
        mapToClassRep(mappings, classRepMap, rev, t)
    }
}

private fun mapToClassRep(mappings: SpoonMapping, classRepMap: MutableMap<SpoonNode, SpoonNode>, rev: Revision, t: CtElement) {
    NodeFactory.setRevisionIfUnset(t, rev)
    val wrapped = NodeFactory.wrap(t)
    val classRep = mappings.getSrc(wrapped)
    if (classRep != null) {
        mapNodes(wrapped, classRep, classRepMap)
    } else {
        mapNodes(wrapped, wrapped, classRepMap)
    }
}

/**
 * Map from to to, including the associated virtual nodes.
 *
 * @param from A SpoonNode.
 * @param to A SpoonNode
 * @param classRepMap The class representatives map.
 */
private fun mapNodes(from: SpoonNode, to: SpoonNode, classRepMap: MutableMap<SpoonNode, SpoonNode>) {
    // map the real nodes
    classRepMap[from] = to

    // map the virtual nodes
    val fromVirtualNodes = from.virtualNodes
    val toVirtualNodes = to.virtualNodes
    for (i in fromVirtualNodes.indices) {
        val fromVirt = fromVirtualNodes[i]
        val toVirt = toVirtualNodes[i]
        if (fromVirt.isListEdge) {
            classRepMap[fromVirt] = toVirt
        } else {
            mapNodes(fromVirt, toVirt, classRepMap)
        }
    }
}

/**
 * A scanner that conservatively expands the class representatives mapping with matches from a left-to-right
 * tree matching. If a node in the left tree is not mapped to base (i.e. self-mapped in the class
 * representatives map), but it is matched with some node in right, then the node in right is mapped
 * to the node in left iff their parents are already mapped, and the node in right is also self-mapped.
 * The node in left remains self-mapped.
 *
 * This must be done by traversing the left tree top-down to allow augmenting mappings to propagate. For
 * example, if both the left and the right revision have added identical methods, then their declarations
 * will be mapped first, and then their contents will be mapped recursively (which is OK as the
 * declarations are now mapped). If one would have started with matches in the bodies of the methods,
 * then these would not be added to the class representatives map as the declarations (i.e. parents)
 * would not yet be mapped.
 *
 * The reason for this conservative use of the left-to-right matchings is that there is otherwise a high
 * probability of unwanted matches. For example, if the left revision adds a parameter to some method,
 * and the right revision adds an identical parameter to another method, then these may be matched,
 * even though they are not related. If that match is put into the class representatives map, there
 * may be some really strange effects on the merge process.
 *
 * @author Simon Larsén
 */
private class ClassRepresentativeAugmenter
/**
 * @param classRepMap The class representatives map, initialized with left-to-base and right-to-base mappings.
 * @param leftRightMatch A tree matching between the left and right revisions, where the left revision is the
 * source and the right revision the destination.
 */(private val classRepMap: MutableMap<SpoonNode, SpoonNode>, private val leftRightMatch: SpoonMapping) : CtScanner() {
    private val forcedMappings: Map<String, SpoonNode>? = null

    /**
     *
     * @param element An element from the left revision.
     */
    override fun scan(element: CtElement?) {
        if (element == null) return

        val left = NodeFactory.wrap(element)
        if (classRepMap[left] === left) {
            val right = leftRightMatch.getDst(left)
            if (right != null && classRepMap[right] === right) {
                val rightParentClassRep = classRepMap[right.parent]
                val leftParentClassRep = classRepMap[left.parent]
                if (leftParentClassRep === rightParentClassRep) {
                    // map right to left
                    mapNodes(right, left, classRepMap)
                }
            }
        }
        super.scan(element)
    }
}
