package se.kth.spork.spoon.wrappers

import se.kth.spork.base3dm.REV
import se.kth.spork.base3dm.Revision
import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.CtExecutable
import spoon.reflect.declaration.CtType
import spoon.reflect.declaration.CtTypeParameter
import spoon.reflect.factory.ModuleFactory.CtUnnamedModule
import spoon.reflect.meta.RoleHandler
import spoon.reflect.meta.impl.RoleHandlerHelper
import spoon.reflect.path.CtRole
import spoon.reflect.reference.CtExecutableReference
import java.util.Arrays
import java.util.Objects
import java.util.TreeMap

/**
 * Factory for wrapping a Spoon [CtElement] in a [SpoonNode].
 *
 * @author Simon Larsén
 */
object NodeFactory {
    val virtualRoot: SpoonNode = Root

    const val WRAPPER_METADATA = "spork_wrapper"
    private var currentKey: Long = 0

    // These are roles that are present in the EXPLODED_TYPES types, but are either not structural
    // or are always present as a single node (such as a method body)
    private val IGNORED_ROLES = setOf( /* START NON-STRUCTURAL ROLES */
        CtRole.IS_IMPLICIT,
        CtRole.IS_DEFAULT,
        CtRole.IS_VARARGS,
        CtRole.IS_FINAL,
        CtRole.IS_SHADOW,
        CtRole.IS_STATIC,
        CtRole.DECLARING_TYPE,
        CtRole.MODIFIER,
        CtRole.EMODIFIER,
        CtRole.NAME,
        CtRole.POSITION, /* END NON-STRUCTURAL ROLES */
        CtRole.BODY, // always present as a single node
        CtRole.NESTED_TYPE, // falls under type member
        CtRole.FIELD, // falls under type member
        CtRole.METHOD, // falls under type member
    )

    private val EXPLODED_TYPES = Arrays.asList(
        CtExecutableReference::class.java,
        CtExecutable::class.java,
        CtType::class.java,
    )
    private var EXPLODED_TYPE_ROLES: Map<Class<out CtElement>, List<CtRole>> = EXPLODED_TYPES.map {
        it to getRoles(it).filter { it !in IGNORED_ROLES }
    }.toMap()

    /**
     * Wrap a CtElement in a CtWrapper. The wrapper is stored in the CtElement's metadata. If a CtElement that has
     * already been wrapped is passed in, then its existing wrapper is returned. In other words, each CtElement gets
     * a single unique CtWrapper.
     *
     * @param elem An element to wrap.
     * @return A wrapper around the CtElement that is more practical for hashing purposes.
     */
    @JvmStatic
    fun wrap(elem: CtElement): SpoonNode {
        return wrapInternal(elem)
    }

    /**
     * Wrap the provided element and forcibly set its parent.
     *
     * This will replace any previous wrapper for this element.
     *
     * @param elem An element to wrap.
     * @param parent The SpoonNode parent of the element.
     * @return A wrapper around a CtElement.
     */
    @JvmStatic
    fun forceWrap(elem: CtElement, parent: SpoonNode): SpoonNode {
        return initializeWrapper(elem, parent)
    }

    /**
     * Clear all non-revision metadata from this element.
     *
     * @param elem An element.
     */
    @JvmStatic
    fun clearNonRevisionMetadata(elem: CtElement) {
        val rev = elem.getMetadata(REV) as Revision
        val metadata: MutableMap<String, Any> = TreeMap()
        metadata[REV] = rev
        elem.setAllMetadata<CtElement>(metadata)
    }

    /**
     * Set the revision of this element only if it is not all ready set.
     *
     * @param elem An element.
     * @param revision A revision.
     */
    @JvmStatic
    fun setRevisionIfUnset(elem: CtElement, revision: Revision?) {
        if (elem.getMetadata(REV) == null) {
            elem.putMetadata<CtElement>(REV, revision)
        }
    }

    private fun wrapInternal(elem: CtElement): Node {
        val wrapper = elem.getMetadata(WRAPPER_METADATA) ?: return initializeWrapper(elem)
        return wrapper as Node
    }

    private fun initializeWrapper(elem: CtElement): Node {
        if (elem is CtUnnamedModule) {
            return initializeWrapper(elem, virtualRoot)
        }
        val spoonParent = elem.parent
        val roleInParent = elem.roleInParent
        val actualParent = wrapInternal(spoonParent)
        val effectiveParent: SpoonNode = if (actualParent.hasRoleNodeFor(roleInParent)) actualParent.getRoleNode(roleInParent) else actualParent
        return initializeWrapper(elem, effectiveParent)
    }

    private fun initializeWrapper(elem: CtElement, parent: SpoonNode): Node {
        val availableChildRoles = getVirtualNodeChildRoles(elem)
        val node = Node(elem, parent, currentKey++, availableChildRoles)
        elem.putMetadata<CtElement>(WRAPPER_METADATA, node)
        return node
    }

    /**
     * Return a list of child nodes that should be exploded into virtual types for the given element.
     *
     * Note that for most types, the list will be empty.
     */
    private fun getVirtualNodeChildRoles(elem: CtElement): List<CtRole>? {
        if (CtTypeParameter::class.java.isAssignableFrom(elem.javaClass)) {
            // we ignore any subtype of CtTypeParameter as exploding them causes a large performance hit
            return emptyList()
        }
        val cls: Class<out CtElement> = elem.javaClass
        for (explodedType in EXPLODED_TYPES) {
            if (explodedType.isAssignableFrom(cls)) {
                return EXPLODED_TYPE_ROLES[explodedType]
            }
        }
        return emptyList()
    }

    private fun getRoles(cls: Class<out CtElement>): List<CtRole> {
        return RoleHandlerHelper.getRoleHandlers(cls).map { obj: RoleHandler -> obj.role }
    }

    /**
     * Create a node that represents the start of the child list of the provided node.
     *
     * @param node A Spoon node.
     * @return The start of the child list of the given node.
     */
    private fun startOfChildList(node: SpoonNode): SpoonNode {
        return ListEdge(node, ListEdge.Side.START)
    }

    /**
     * Create a node that represents the end of the child list of the provided node.
     *
     * @param elem A Spoon node.
     * @return The end of the child list of the given node.
     */
    private fun endOfChildList(elem: SpoonNode): SpoonNode {
        return ListEdge(elem, ListEdge.Side.END)
    }

    /**
     * Base class for any [SpoonNode] that has a child list.
     */
    private abstract class ParentSpoonNode internal constructor() : SpoonNode {
        override val startOfChildList: SpoonNode = startOfChildList(this)
        override val endOfChildList: SpoonNode = endOfChildList(this)
    }

    /**
     * A simple wrapper class for a Spoon CtElement. The reason it is needed is that the 3DM merge implementation
     * uses lookup tables, and CtElements have very heavy-duty equals and hash functions. For the purpose of 3DM merge,
     * only reference equality is needed, not deep equality.
     *
     * This class should only be instantiated by [.wrap].
     */
    private class Node internal constructor(override val element: CtElement, override val parent: SpoonNode, private val key: Long, virtualNodeChildRoles: List<CtRole>?) : ParentSpoonNode() {
        private val virtualRoleChildNodes: MutableMap<CtRole, RoleNode>
        private val role: CtRole

        override val virtualNodes: List<SpoonNode>
            get() = listOf(startOfChildList) + virtualRoleChildNodes.values.toList() + listOf(endOfChildList)

        override val isVirtual: Boolean = false

        override val revision: Revision
            get() {
                if (element.getMetadata(REV) == null) {
                    return Revision.BASE
                }
                return element.getMetadata(REV) as Revision
            }

        override fun toString(): String {
            val longRep = element.toString()
            if (longRep.contains("\n")) {
                val shortRep = element.shortRepresentation.split("\\.".toRegex()).toTypedArray()
                return shortRep[shortRep.size - 1]
            }
            return longRep
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val wrapper = other as Node
            return key == wrapper.key
        }

        override fun hashCode(): Int {
            return Objects.hash(key)
        }

        fun getRoleNode(role: CtRole): RoleNode {
            return virtualRoleChildNodes[role] ?: throw IllegalArgumentException("No role node for $role")
        }

        fun hasRoleNodeFor(role: CtRole?): Boolean {
            return role != null && virtualRoleChildNodes.containsKey(role)
        }

        init {
            virtualRoleChildNodes = TreeMap()
            for (role in virtualNodeChildRoles!!) {
                virtualRoleChildNodes[role] = RoleNode(role, this)
            }
            try {
                role = when (parent) {
                    is Root -> CtRole.DECLARED_MODULE
                    else -> element.roleInParent
                }
            } catch (e: IllegalStateException) {
                throw e
            }
        }
    }

    /**
     * The root virtual node. This is a singleton, there should only be the one that exists in [.ROOT].
     */
    private object Root : ParentSpoonNode() {
        override val element: CtElement
            get() = throw UnsupportedOperationException("The virtual root has no parent")

        override val parent: SpoonNode
            get() = throw UnsupportedOperationException("The virtual root has no parent")

        override fun toString(): String = "ROOT"

        override val revision: Revision
            get() = Revision.BASE

        override val isVirtual: Boolean = true

        override val virtualNodes: List<SpoonNode> = listOf(startOfChildList(this), endOfChildList(this))
    }

    /**
     * A special SpoonNode that marks the start or end of a child list.
     */
    private class ListEdge internal constructor( // the parent of the child list
        override val parent: SpoonNode,
        private val side: Side,
    ) : SpoonNode {
        enum class Side {
            START, END
        }

        override val element: CtElement
            get() = throw UnsupportedOperationException("Can't get element from a list edge")

        override val revision: Revision
            get() = parent.revision

        override val virtualNodes: List<SpoonNode>
            get() = throw UnsupportedOperationException("Can't get virtual nodes from a list edge")

        override val isEndOfList: Boolean = side == Side.END

        override val isStartOfList: Boolean = side == Side.START

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val listEdge = other as ListEdge
            return parent == listEdge.parent &&
                side == listEdge.side
        }

        override fun hashCode(): Int {
            return Objects.hash(parent, side)
        }

        override fun toString(): String {
            return side.toString()
        }

        override val startOfChildList: SpoonNode
            get() = throw UnsupportedOperationException("A list edge has no child list")

        override val endOfChildList: SpoonNode
            get() = throw UnsupportedOperationException("A list edge has no child list")
    }

    /**
     * A RoleNode is a virtual node used to separate child lists in nodes with multiple types of child lists. See
     * https://github.com/KTH/spork/issues/132 for details.
     */
    private class RoleNode internal constructor(private val role: CtRole, override val parent: Node) : ParentSpoonNode() {
        override val element: CtElement
            get() = throw UnsupportedOperationException("Can't get element from a RoleNode")

        override val revision: Revision = parent.revision

        override val virtualNodes: List<SpoonNode> = listOf(startOfChildList(this), endOfChildList(this))

        override fun toString(): String = "RoleNode#$role"

        override val isVirtual: Boolean = true

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val roleNode = other as RoleNode
            return parent == roleNode.parent &&
                role == roleNode.role
        }

        override fun hashCode(): Int = Objects.hash(parent, role)
    }
}
