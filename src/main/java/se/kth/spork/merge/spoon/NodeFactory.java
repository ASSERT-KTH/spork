package se.kth.spork.merge.spoon;

import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.ModuleFactory;

import java.util.Objects;

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
    public static SpoonNode wrap(CtElement elem) {
        Object wrapper = elem.getMetadata(WRAPPER_METADATA);

        if (wrapper == null) {
            wrapper = new Node(elem, currentKey++);
            elem.putMetadata(WRAPPER_METADATA, wrapper);
        }

        return (SpoonNode) wrapper;
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
     *
     * This class should only be instantiated with the CtWrapperFactory singleton.
     */
    private static class Node implements SpoonNode {
        private final CtElement element;
        private final long key;

        Node(CtElement element, long key) {
            this.element = element;
            this.key = key;
        }

        @Override
        public CtElement getElement() {
            return element;
        }

        @Override
        public SpoonNode getParent() {
            if (element instanceof ModuleFactory.CtUnnamedModule)
                return NodeFactory.ROOT;
            return wrap(element.getParent());
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
}
