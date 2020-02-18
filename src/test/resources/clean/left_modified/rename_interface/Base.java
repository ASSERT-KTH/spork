/**
 * A generic interface for some collection-esque data type.
 *
 * @author Simon Larsén
 */
public interface Collection<T> {
    /**
     * Add an element to this collection.
     *
     * @param e The element to add.
     * @return true iff element was added.
     */
    boolean add(T e);

    /**
     * Remove an element from this collection.
     *
     * @param e The element to remove.
     * @return true iff the element was present (and removed).
     */
    boolean remove(T e);

    /**
     * Check if this collection contains some element.
     *
     * @param o The element whose presence to check for.
     * @return true iff o was in the collection.
     */
    boolean contains(Object o);
}
