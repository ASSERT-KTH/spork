package se.kth.spork.spoon.wrappers;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.base3dm.TdmMerge;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.ModuleFactory;
import spoon.reflect.path.CtRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wraps a CtElement and stores the wrapper in the CtElement's metadata.
 *
 * @author Simon Larsén
 */
public class NodeFactory {
    public static final String WRAPPER_METADATA = "spork_wrapper";
    private static long currentKey = 0;
    public static final SpoonNode ROOT = new Root();

    /**
     * Wrap a CtElement in a CtWrapper. The wrapper is stored in the CtElement's metadata. If a CtElement that has
     * already been wrapped is passed in, then its existing wrapper is returned. In other words, each CtElement gets
     * a single unique CtWrapper.
     *
     * @param elem An element to wrap.
     * @return A wrapper around the CtElement that is more practical for hashing purposes.
     */
    public static Node wrap(CtElement elem) {
        Object wrapper = elem.getMetadata(WRAPPER_METADATA);

        if (wrapper == null) {
            //throw new IllegalStateException("wrapper not initialized for " + elem);
            CtElement parent = elem.getParent();
            return initializeWrapper(elem, parent == null ? ROOT : wrap(parent));
        }

        return (Node) wrapper;
    }

    public static Node initializeRoledWrapper(CtElement elem, Node parent) {
        SpoonNode effectiveParent = parent.getRoleNode(elem.getRoleInParent());
        return initializeWrapper(elem, effectiveParent);
    }

    public static Node initializeWrapper(CtElement elem, SpoonNode parent) {
        Node node = new Node(elem, parent, currentKey++);
        elem.putMetadata(WRAPPER_METADATA, node);
        return node;
    }

    /**
     * Create a node that represents the start of the child list of the provided node.
     *
     * @param node A Spoon node.
     * @return The start of the child list of the given node.
     */
    public static SpoonNode startOfChildList(SpoonNode node) {
        return new ListEdge(node, ListEdge.Side.START);
    }

    /**
     * Create a node that represents the end of the child list of the provided node.
     *
     * @param elem A Spoon node.
     * @return The end of the child list of the given node.
     */
    public static SpoonNode endOfChildList(SpoonNode elem) {
        return new ListEdge(elem, ListEdge.Side.END);
    }

    /**
     * A simple wrapper class for a Spoon CtElement. The reason it is needed is that the 3DM merge implementation
     * uses lookup tables, and CtElements have very heavy-duty equals and hash functions. For the purpose of 3DM merge,
     * only reference equality is needed, not deep equality.
     * <p>
     * This class should only be instantiated {@link NodeFactory#wrap(CtElement)}.
     */
    public static class Node implements SpoonNode {
        private final CtElement element;
        private final long key;
        private final SpoonNode parent;
        private final Map<CtRole, RoleNode> childRoles;
        private final CtRole role;

        Node(CtElement element, SpoonNode parent, long key) {
            this.element = element;
            this.key = key;
            childRoles = new TreeMap<>();
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
                            childRoles.values().stream()
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
            RoleNode roleNode = childRoles.get(role);
            if (roleNode == null) {
                roleNode = new RoleNode(role, this);
                childRoles.put(role, roleNode);
            }
            return roleNode;
        }

        public List<RoleNode> getChildRoles() {
            return new ArrayList<>(childRoles.values());
        }
    }

    private static class Root implements SpoonNode {
        @Override
        public CtElement getElement() {
            return null;
        }

        @Override
        public SpoonNode getParent() {
            return null;
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
            return null;
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
    }

    public static class RoleNode implements SpoonNode {
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
