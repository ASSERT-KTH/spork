package se.kth.spork.spoon.wrappers;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.base3dm.TdmMerge;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.factory.ModuleFactory;
import spoon.reflect.meta.RoleHandler;
import spoon.reflect.meta.impl.RoleHandlerHelper;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtExecutableReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Factory for wrapping a Spoon {@link CtElement} in a {@link SpoonNode}.
 *
 * @author Simon Larsén
 */
public class NodeFactory {
    public static final String WRAPPER_METADATA = "spork_wrapper";
    private static long currentKey = 0;
    public static final SpoonNode ROOT = new Root();

    private static final Map<Class<? extends CtElement>, List<CtRole>> EXPLODED_TYPE_ROLES;
    private static final List<Class<? extends CtElement>> EXPLODED_TYPES = Arrays.asList(
            CtExecutableReference.class, CtExecutable.class, CtType.class
    );

    // These are roles that are present in the EXPLODED_TYPES types, but are either not structural
    // or are always present as a single node (such as a method body)
    private static final Set<CtRole> IGNORED_ROLES = Stream.of(
            /* START NON-STRUCTURAL ROLES */
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
            CtRole.POSITION,
            /* END NON-STRUCTURAL ROLES */
            CtRole.BODY,        // always present as a single node
            CtRole.NESTED_TYPE, // falls under type member
            CtRole.FIELD,       // falls under type member
            CtRole.METHOD       // falls under type member
    ).collect(Collectors.toSet());

    static {
        Map<Class<? extends CtElement>, List<CtRole>> rolesPerClass = new HashMap<>();
        for (Class<? extends CtElement> cls : EXPLODED_TYPES) {
            rolesPerClass.put(cls, getRoles(cls).filter(role -> !IGNORED_ROLES.contains(role)).collect(Collectors.toList()));
        }
        EXPLODED_TYPE_ROLES = Collections.unmodifiableMap(rolesPerClass);
    }

    /**
     * Wrap a CtElement in a CtWrapper. The wrapper is stored in the CtElement's metadata. If a CtElement that has
     * already been wrapped is passed in, then its existing wrapper is returned. In other words, each CtElement gets
     * a single unique CtWrapper.
     *
     * @param elem An element to wrap.
     * @return A wrapper around the CtElement that is more practical for hashing purposes.
     */
    public static SpoonNode wrap(CtElement elem) {
        return wrapInternal(elem);
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
    public static SpoonNode forceWrap(CtElement elem, SpoonNode parent) {
        return initializeWrapper(elem, parent);
    }

    /**
     * Clear all non-revision metadata from this element.
     *
     * @param elem An element.
     */
    public static void clearNonRevisionMetadata(CtElement elem) {
        Revision rev = (Revision) elem.getMetadata(TdmMerge.REV);
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put(TdmMerge.REV, rev);
        elem.setAllMetadata(metadata);
    }

    /**
     * Set the revision of this element only if it is not all ready set.
     *
     * @param elem An element.
     * @param revision A revision.
     */
    public static void setRevisionIfUnset(CtElement elem, Revision revision) {
        if (elem.getMetadata(TdmMerge.REV) == null) {
            elem.putMetadata(TdmMerge.REV, revision);
        }
    }

    private static Node wrapInternal(CtElement elem) {
        Object wrapper = elem.getMetadata(WRAPPER_METADATA);

        if (wrapper == null) {
            return initializeWrapper(elem);
        }

        return (Node) wrapper;
    }

    private static Node initializeWrapper(CtElement elem) {
        if (elem instanceof ModuleFactory.CtUnnamedModule)
            return initializeWrapper(elem, ROOT);

        CtElement spoonParent = elem.getParent();
        CtRole roleInParent = elem.getRoleInParent();
        Node actualParent = wrapInternal(spoonParent);
        SpoonNode effectiveParent = actualParent.hasRoleNodeFor(roleInParent) ?
                        actualParent.getRoleNode(roleInParent) : actualParent;
        return initializeWrapper(elem, effectiveParent);
    }

    private static Node initializeWrapper(CtElement elem, SpoonNode parent) {
        List<CtRole> availableChildRoles = getVirtualNodeChildRoles(elem);
        Node node = new Node(elem, parent, currentKey++, availableChildRoles);
        elem.putMetadata(WRAPPER_METADATA, node);
        return node;
    }

    /**
     * Return a list of child nodes that should be exploded into virtual types for the given element.
     *
     * Note that for most types, the list will be empty.
     */
    private static List<CtRole> getVirtualNodeChildRoles(CtElement elem) {
        if (CtTypeParameter.class.isAssignableFrom(elem.getClass())) {
            // we ignore any subtype of CtTypeParameter as exploding them causes a large performance hit
            return Collections.emptyList();
        }

        Class<? extends CtElement> cls = elem.getClass();
        for (Class<? extends CtElement> explodedType : EXPLODED_TYPES) {
            if (explodedType.isAssignableFrom(cls)) {
                return EXPLODED_TYPE_ROLES.get(explodedType);
            }
        }
        return Collections.emptyList();
    }

    private static Stream<CtRole> getRoles(Class<? extends CtElement> cls) {
        return RoleHandlerHelper.getRoleHandlers(cls).stream().map(RoleHandler::getRole);
    }

    /**
     * Create a node that represents the start of the child list of the provided node.
     *
     * @param node A Spoon node.
     * @return The start of the child list of the given node.
     */
    private static SpoonNode startOfChildList(SpoonNode node) {
        return new ListEdge(node, ListEdge.Side.START);
    }

    /**
     * Create a node that represents the end of the child list of the provided node.
     *
     * @param elem A Spoon node.
     * @return The end of the child list of the given node.
     */
    private static SpoonNode endOfChildList(SpoonNode elem) {
        return new ListEdge(elem, ListEdge.Side.END);
    }

    /**
     * Base class for any {@link SpoonNode} that has a child list.
     */
    private static abstract class ParentSpoonNode implements SpoonNode {
        private final SpoonNode startOfChildList;
        private final SpoonNode endOfChildList;

        ParentSpoonNode() {
            this.startOfChildList = startOfChildList(this);
            this.endOfChildList = endOfChildList(this);
        }

        @Override
        public SpoonNode getStartOfChildList() {
            return startOfChildList;
        }

        @Override
        public SpoonNode getEndOfChildList() {
            return endOfChildList;
        }
    }

    /**
     * A simple wrapper class for a Spoon CtElement. The reason it is needed is that the 3DM merge implementation
     * uses lookup tables, and CtElements have very heavy-duty equals and hash functions. For the purpose of 3DM merge,
     * only reference equality is needed, not deep equality.
     *
     * This class should only be instantiated by {@link #wrap(CtElement)}.
     */
    private static class Node extends ParentSpoonNode {
        private final CtElement element;
        private final long key;
        private final SpoonNode parent;
        private final Map<CtRole, RoleNode> virtualRoleChildNodes;
        private final CtRole role;

        private final SpoonNode startOfChildList;
        private final SpoonNode endOfChildList;

        Node(CtElement element, SpoonNode parent, long key, List<CtRole> virtualNodeChildRoles) {
            this.element = element;
            this.key = key;

            virtualRoleChildNodes = new TreeMap<>();
            for (CtRole role : virtualNodeChildRoles) {
                virtualRoleChildNodes.put(role, new RoleNode(role, this));
            }

            startOfChildList = startOfChildList(this);
            endOfChildList = endOfChildList(this);

            this.role = element.getRoleInParent();
            this.parent = parent;
        }

        @Override
        public CtElement getElement() {
            return element;
        }

        @Override
        public SpoonNode getParent() {
            return parent;
        }

        @Override
        public String toString() {
            if (element == null)
                return "null";

            String longRep = element.toString();
            if (longRep.contains("\n")) {
                String[] shortRep = element.getShortRepresentation().split("\\.");
                return shortRep[shortRep.length - 1];
            }
            return longRep;
        }

        @Override
        public Revision getRevision() {
            return (Revision) element.getMetadata(TdmMerge.REV);
        }

        @Override
        public boolean isVirtual() {
            return false;
        }

        @Override
        public List<SpoonNode> getVirtualNodes() {
            return Stream.concat(
                    Stream.concat(
                            Stream.of(NodeFactory.startOfChildList(this)),
                            virtualRoleChildNodes.values().stream()
                    ),
                    Stream.of(NodeFactory.endOfChildList(this)))
                    .collect(Collectors.toList());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node wrapper = (Node) o;
            return key == wrapper.key;
        }

        @Override
        public int hashCode() {
            return Objects.hash(key);
        }

        public RoleNode getRoleNode(CtRole role) {
            RoleNode roleNode = virtualRoleChildNodes.get(role);
            if (roleNode == null) {
                throw new IllegalArgumentException("No role node for " + role);
            }
            return roleNode;
        }

        boolean hasRoleNodeFor(CtRole role) {
            return role != null && virtualRoleChildNodes.containsKey(role);
        }

        @Override
        public SpoonNode getStartOfChildList() {
            return startOfChildList;
        }

        @Override
        public SpoonNode getEndOfChildList() {
            return endOfChildList;
        }
    }

    /**
     * The root virtual node. This is a singleton, there should only be the one that exists in {@link #ROOT}.
     */
    private static class Root extends ParentSpoonNode {
        @Override
        public CtElement getElement() {
            return null;
        }

        @Override
        public SpoonNode getParent() {
            throw new UnsupportedOperationException("The virtual root has no parent");
        }

        @Override
        public String toString() {
            return "ROOT";
        }

        @Override
        public Revision getRevision() {
            throw new UnsupportedOperationException("The virtual root has no revision");
        }

        @Override
        public boolean isVirtual() {
            return true;
        }

        @Override
        public List<SpoonNode> getVirtualNodes() {
            return Arrays.asList(NodeFactory.startOfChildList(this), NodeFactory.endOfChildList(this));
        }
    }

    /**
     * A special SpoonNode that marks the start or end of a child list.
     */
    private static class ListEdge implements SpoonNode {
        private enum Side {
            START, END;
        }

        // the parent of the child list
        private SpoonNode parent;
        private Side side;

        ListEdge(SpoonNode parent, Side side) {
            this.parent = parent;
            this.side = side;
        }

        @Override
        public CtElement getElement() {
            return null;
        }

        @Override
        public SpoonNode getParent() {
            return parent;
        }

        @Override
        public Revision getRevision() {
            return parent.getRevision();
        }

        @Override
        public List<SpoonNode> getVirtualNodes() {
            throw new UnsupportedOperationException("Can't get virtual nodes from a list edge");
        }

        @Override
        public boolean isEndOfList() {
            return side == Side.END;
        }

        @Override
        public boolean isStartOfList() {
            return side == Side.START;
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ListEdge listEdge = (ListEdge) o;
            return Objects.equals(parent, listEdge.parent) &&
                    side == listEdge.side;
        }

        @Override
        public int hashCode() {
            return Objects.hash(parent, side);
        }

        @Override
        public String toString() {
            return side.toString();
        }

        @Override
        public SpoonNode getStartOfChildList() {
            throw new UnsupportedOperationException("A list edge has no child list");
        }

        @Override
        public SpoonNode getEndOfChildList() {
            throw new UnsupportedOperationException("A list edge has no child list");
        }
    }

    /**
     * A RoleNode is a virtual node used to separate child lists in nodes with multiple types of child lists. See
     * https://github.com/KTH/spork/issues/132 for details.
     */
    private static class RoleNode extends ParentSpoonNode {
        private final Node parent;
        private final CtRole role;

        RoleNode(CtRole role, Node parent) {
            this.role = role;
            this.parent = parent;
        }

        @Override
        public CtElement getElement() {
            throw new UnsupportedOperationException("Can't get element from a RoleNode");
        }

        @Override
        public SpoonNode getParent() {
            return parent;
        }

        @Override
        public Revision getRevision() {
            return parent.getRevision();
        }

        @Override
        public List<SpoonNode> getVirtualNodes() {
            return Arrays.asList(NodeFactory.startOfChildList(this), NodeFactory.endOfChildList(this));
        }

        @Override
        public String toString() {
            return "RoleNode#" + role.toString();
        }

        @Override
        public boolean isVirtual() {
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RoleNode roleNode = (RoleNode) o;
            return Objects.equals(parent, roleNode.parent) &&
                    role == roleNode.role;
        }

        @Override
        public int hashCode() {
            return Objects.hash(parent, role);
        }
    }
}
