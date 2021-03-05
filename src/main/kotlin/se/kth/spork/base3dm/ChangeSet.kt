package se.kth.spork.base3dm

import java.util.Collections

/**
 * Represents a change set in 3DM merge. While a change set in pure 3DM merge is just all content tuples and PCS
 * triples put together, this change set separates content and structure into separate sets, and also introduces
 * some helper functionality to keep track of conflicts and enable faster lookup of PCS triples.
 *
 * @author Simon Larsén
 */
class ChangeSet<T : ListNode, V>(private val classRepMap: Map<T, T>, getContent: (T) -> V, vararg trees: Set<Pcs<T>>) {
    private val successors: MutableMap<T, MutableSet<Pcs<T>>>
    private val predecessors: MutableMap<T, MutableSet<Pcs<T>>>

    private val _structuralConflicts: MutableMap<Pcs<T>, MutableSet<Pcs<T>>>
    val structuralConflicts: Map<Pcs<T>, Set<Pcs<T>>>
        get() = _structuralConflicts.toMap()

    private val _pcsSet: MutableSet<Pcs<T>>
    val pcsSet: Set<Pcs<T>>
        get() = _pcsSet.toSet()

    private val _content: MutableMap<T, MutableSet<Content<T, V>>>
    val contents: Map<T, MutableSet<Content<T, V>>>
        get() = HashMap(_content)

    /**
     * @param pcs A PCS triple.
     * @return All PCSes that are root conflicting with the provided PCS.
     */
    fun getOtherRoots(pcs: Pcs<T>): List<Pcs<T>> {
        return sequenceOf(pcs.predecessor, pcs.successor).flatMap { node ->
            sequenceOf(predecessors[node], successors[node]).filterNotNull().flatMap {
                it.asSequence()
            }.filter {
                it.root != pcs.root
            }
        }.toList()
    }

    /**
     * @param pcs A PCS triple.
     * @return All PCSes that are successor conflicting with the provided PCS.
     */
    fun getOtherSuccessors(pcs: Pcs<T>): List<Pcs<T>> {
        return (predecessors[pcs.predecessor] ?: setOf<Pcs<T>>()).filter {
            it.successor != pcs.successor
        }.toList()
    }

    /**
     * @param pcs A PCS triple.
     * @return All PCSes that are predecessor conflicting with the provided PCS.
     */
    fun getOtherPredecessors(pcs: Pcs<T>): List<Pcs<T>> {
        return (successors[pcs.successor] ?: emptySet<Pcs<T>>()).filter {
            it.predecessor != pcs.predecessor
        }.toList()
    }

    /**
     * @param node A node..
     * @return The content associated with the argument node, or an empty set if no content was associated.
     */
    fun getContent(node: T): Set<Content<T, V>> {
        return Collections.unmodifiableSet(_content[node] ?: emptySet())
    }

    /**
     * @param pcs A PCS triple.
     * @return true iff the argument is contained in this T*.
     */
    operator fun contains(pcs: Pcs<T>): Boolean {
        return predecessors[pcs.predecessor]?.contains(pcs) == true
    }

    /**
     * @param cont A Content container.
     * @return true iff the argument is contained in this T*.
     */
    operator fun contains(cont: Content<T, V>): Boolean {
        return _content[cont.context.predecessor]?.contains(cont) == true
    }

    /**
     * Set the content for some pcs triple, overwriting anything that was there previously.
     *
     * @param node A node to associate the content with. This is the key in the backing map.
     * @param nodeContents A set of content values to associate with the node.
     */
    fun setContent(node: T, nodeContents: MutableSet<Content<T, V>>) {
        _content[node] = nodeContents
    }

    /**
     * Remove the PCS from all lookup tables, except for the contents table. Also remove it from the *-set.
     *
     * @param pcs A PCS triple.
     */
    fun remove(pcs: Pcs<T>) {
        val pred = pcs.predecessor
        val succ = pcs.successor
        predecessors[pred]!!.remove(pcs)
        successors[succ]!!.remove(pcs)
        _pcsSet.remove(pcs)
    }

    /**
     * @param cont Content to remove from this T*.
     */
    fun remove(cont: Content<T, V>) {
        _content[cont.context.predecessor]!!.remove(cont)
    }

    /**
     * Register a conflict in a bi-directional lookup table.
     *
     * @param first A PCS triple that conflicts with second.
     * @param second A PCS triple that conflicts with first.
     */
    fun registerStructuralConflict(first: Pcs<T>, second: Pcs<T>) {
        addToLookupTable(first, second, _structuralConflicts)
        addToLookupTable(second, first, _structuralConflicts)
    }

    /**
     * Add a tree to this T*. This entails converting the entire PCS tree to its class representatives. Each
     * "class representative PCS" is then added to the predecessor and successor lookup tables, and their contents
     * are added to the content lookup table.
     *
     * @param tree A PCS tree structure.
     * @param getContent A function that returns the content of a T node.
     */
    private fun add(tree: Set<Pcs<T>>, getContent: (T) -> V) {
        for (pcs in tree) {
            val classRepPcs = addToStar(pcs)
            val pred = pcs.predecessor
            val classRepPred = classRepPcs.predecessor
            val classRepSucc = classRepPcs.successor
            addToLookupTable(classRepPred, classRepPcs, predecessors)
            addToLookupTable(classRepSucc, classRepPcs, successors)
            if (!pred.isVirtual) {
                val c = Content(pcs, getContent(pred), pred.revision)
                addToLookupTable(classRepPred, c, _content)
            }
        }
    }

    private fun addToStar(pcs: Pcs<T>): Pcs<T> {
        val root = classRepMap[pcs.root] ?: error("${pcs.root} not in class representatives")
        val pred = classRepMap[pcs.predecessor] ?: error("${pcs.predecessor} not in class representatives")
        val succ = classRepMap[pcs.successor] ?: error("${pcs.successor} not in class representatives")
        val classRepPcs = Pcs(root, pred, succ, pcs.revision)
        _pcsSet.add(classRepPcs)
        return classRepPcs
    }

    companion object {
        private fun <K, V> addToLookupTable(key: K, `val`: V, lookup: MutableMap<K, MutableSet<V>>) {
            val values = lookup.getOrDefault(key, HashSet())!!
            if (values.isEmpty()) lookup[key] = values
            values.add(`val`)
        }
    }

    /**
     * Create a T* from the provided trees, using the class representatives map to map each node to its class
     * representative.
     *
     * @param classRepMap A map mapping each node to its class representative.
     * @param getContent A function for getting content from
     * @param trees The trees to add to this T*.
     */
    init {
        successors = HashMap()
        predecessors = HashMap()
        _content = HashMap()
        _pcsSet = HashSet()
        _structuralConflicts = HashMap()
        trees.forEach { t: Set<Pcs<T>> -> add(t, getContent) }
    }
}
