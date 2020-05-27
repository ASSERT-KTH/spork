package se.kth.spork.base3dm;

import java.util.Objects;

/**
 * Container for a tree node's content. The content value belongs to the predecessor of the context PCS.
 *
 * @author Simon Larsén
 */
public class Content<T extends ListNode,V> {
    private final Pcs<T> context;
    private final V value;
    private final Revision revision;

    /**
     * Create a content container.
     *
     * @param context The context of this content. The value is associated with the predecessor of the context.
     * @param value The value of the this content.
     */
    public Content(Pcs<T> context, V value, Revision revision) {
        this.context = context;
        this.value = value;
        this.revision = revision;
    }

    public Pcs<T> getContext() {
        return context;
    }

    public Revision getRevision() {
        return revision;
    }

    public V getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("rawtypes")
        Content content = (Content) o;
        return Objects.equals(context, content.context) &&
                Objects.equals(value, content.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(context, value);
    }

    @Override
    public String toString() {
        return "C{" +
                "context=" + context +
                ", value=" + value +
                '}';
    }
}
