package se.kth.spork.merge.spoon;

import spoon.reflect.declaration.CtElement;

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
            wrapper = new SpoonNode(elem, currentKey++);
            elem.putMetadata(WRAPPER_METADATA, wrapper);
        }

        return (SpoonNode) wrapper;
    }
}
