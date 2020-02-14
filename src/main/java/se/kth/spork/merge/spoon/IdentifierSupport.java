package se.kth.spork.merge.spoon;

import spoon.reflect.declaration.CtElement;

/**
 * Support class for hashing Spoon objects. Each Spoon object gets a unique key that's stored in its metadata.
 *
 * This class is not thread safe!
 *
 * @author Simon Larsén
 */
public class IdentifierSupport {
    private static IdentifierSupport object;
    private static final String KEY_METADATA = "spoon_object_key";
    private long currentKey;


    // hide default constructor for singleton pattern
    private IdentifierSupport() {
        currentKey = 0;
    }

    /**
     * Get the singleton instance of this class.
     *
     * @return The singleton IdentifierSupport.
     */
    public static IdentifierSupport getInstance() {
        if (object == null)
            object = new IdentifierSupport();
        return object;
    }

    /**
     * Get the key from a Spoon object. If the key is not initialized, it will be. The key is stored as metadata
     * on the object.
     *
     * @param elem A Spoon object.
     * @return The unique ide
     */
    public Long getKey(CtElement elem) {
        if (elem == null)
            return null;

        Object key = elem.getMetadata(KEY_METADATA);
        if (key == null)
            key = initializeKey(elem);
        return (Long) key;
    }

    private Long initializeKey(CtElement elem) {
        Long key = currentKey++;
        elem.putMetadata(KEY_METADATA, key);
        return key;
    }

}
