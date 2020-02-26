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
                return null;
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
}
