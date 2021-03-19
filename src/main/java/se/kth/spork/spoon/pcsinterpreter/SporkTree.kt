package se.kth.spork.spoon.pcsinterpreter

import se.kth.spork.spoon.wrappers.NodeFactory.virtualRoot
import se.kth.spork.base3dm.Content
import kotlin.jvm.JvmOverloads
import se.kth.spork.spoon.wrappers.SpoonNode
import se.kth.spork.spoon.wrappers.RoledValues
import se.kth.spork.spoon.conflict.StructuralConflict
import se.kth.spork.base3dm.Revision
import spoon.reflect.factory.ModuleFactory.CtUnnamedModule
import spoon.reflect.CtModelImpl.CtRootPackage
import java.util.*

/**
 * A Spork tree is an intermediate representation used to bridge conversion from PCS to Spoon. It is
 * essentially a greatly simplified AST that also offers some support for representing structural
 * conflicts as well as keeping track of which revisions it consists of.
 *
 * @author Simon Larsén
 */
class SporkTree @JvmOverloads constructor(
    val node: SpoonNode,
    val content: Set<Content<SpoonNode, RoledValues>>,
    structuralConflict: StructuralConflict? = null
) {
    val structuralConflict: Optional<StructuralConflict> = Optional.ofNullable(structuralConflict)

    private val _children: MutableList<SporkTree> = mutableListOf()
    val children: List<SporkTree>
        get() = _children.toList()

    private val _revisions: MutableSet<Revision> = TreeSet()
    var revisions: Set<Revision>
        get() = _revisions
        set(value) {
            _revisions.clear()
            _revisions.addAll(value)
        }

    fun hasStructuralConflict(): Boolean {
        return structuralConflict.isPresent
    }

    fun addChild(child: SporkTree) {
        child.revisions.forEach(this::addRevision)
        _children.add(child)
    }

    fun addRevision(revision: Revision) {
        _revisions.add(revision)
    }

    /**
     * @return True if the subtree consists of only a single revision, AND the subtree is not rooted
     * in the unnamed module or the root package. These should never be considered single
     * revision as they must be replaced with new elements.
     */
    val isSingleRevisionSubtree: Boolean
        get() {
            val element = node.element
            return (!(element is CtUnnamedModule
                    || element is CtRootPackage)
                    && revisions.size == 1)
        }
    val singleRevision: Revision
        get() {
            check(revisions.size == 1) { "Not a single revision subtree" }
            return revisions.iterator().next()
        }

    init {
        if (node !== virtualRoot) {
            _revisions.add(node.revision)
        }
        content.map { it.revision }.forEach(this::addRevision)
    }
}