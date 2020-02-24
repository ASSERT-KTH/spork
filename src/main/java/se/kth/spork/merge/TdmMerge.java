package se.kth.spork.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * An implementation of the 3DM merge algorithm by Tancred Lindholm. For details on 3DM merge, see the paper
 * <a href="https://doi.org/10.1145/1030397.1030399>A three-way merge for XML documents</a>.
 *
 * @author Simon Larsén
 */
public class TdmMerge {
    public static final String REV = "rev";

    private static final Logger LOGGER = LoggerFactory.getLogger(TdmMerge.class);

    /**
     * Attempt to resolve a raw merge by incrementally removing inconsistencies. The input delta is the raw merge, which
     * typically is an inconsistent PCS tree. When the algorithm has finished running, delta should be a forest of
     * consistent PCS trees. The forest consists of the merged tree, as well as any deleted subtrees.
     *
     * A structural or content conflict means that 3DM was unable to create a consistent PCS structure.
     *
     * @param base The base revision.
     * @param delta The raw merge.
     */
    public static <T,V> void resolveRawMerge(TStar<T,V> base, TStar<T,V> delta) {
        List<Conflict<Content<T,V>>> contentConflicts = new ArrayList<>();

        for (Pcs<T> pcs : delta.getStar()) {
            if (!delta.contains(pcs)) // was removed as otherPcs
                continue;
            if (delta.inStructuralConflict(pcs)) // was registered in conflict as otherPcs
                continue;

            if (pcs.getPredecessor() != null) {
                Set<Content<T,V>> contents = delta.getContent(pcs);
                if (contents != null && contents.size() > 1) {
                    handleContentConflict(contents, base).ifPresent(contentConflicts::add);
                }
            }

            Optional<Pcs<T>> other = delta.getOtherRoot(pcs);
            if (!other.isPresent())
                other = delta.getOtherPredecessor(pcs);
            if (!other.isPresent())
                other = delta.getOtherSuccessor(pcs);

            if (other.isPresent()) {
                Pcs<T> otherPcs = other.get();

                if (base.contains(otherPcs)) {
                    delta.remove(otherPcs);
                } else if (base.contains(pcs)) {
                    delta.remove(pcs);
                } else {
                    delta.registerStructuralConflict(pcs, otherPcs);
                }
            }
        }


        Map<Pcs<T>, Set<Pcs<T>>> structuralConflicts = delta.getStructuralConflicts();
        if (!contentConflicts.isEmpty()) {
            LOGGER.warn("CONTENT CONFLICTS DETECTED: " + contentConflicts);
        }
        if (!structuralConflicts.isEmpty()) {
            throw new IllegalStateException("STRUCTURAL CONFLICTS DETECTED: " + structuralConflicts);
        }
    }

    /**
     * Handle content conflicts, i.e. the same node is associated with multiple (potentially equivalent) contents.
     *
     * TODO have this method modify the contents in a less dirty way
     */
    private static <T,V> Optional<Conflict<Content<T,V>>> handleContentConflict(Set<Content<T,V>> contents, TStar<T,V> base) {
        if (contents.size() > 3)
            throw new IllegalArgumentException("expected at most 3 pieces of conflicting content, got: " + contents);

        // contents equal to that in base never takes precedence over left and right revisions
        Optional<Content<T,V>> basePcsOpt = contents.stream().filter(base::contains).findAny();

        basePcsOpt.ifPresent(content -> contents.removeIf(c -> Objects.equals(c.getValue(), content.getValue())));

        if (contents.size() == 0) { // everything was equal to base, re-add
            contents.add(basePcsOpt.get());
        } else if (contents.size() == 2) { // both left and right have been modified from base
            Iterator<Content<T,V>> it = contents.iterator();
            Content<T,V> first = it.next();
            Content<T,V> second = it.next();
            if (second.getValue().equals(first.getValue()))
                it.remove();
        } else if (contents.size() > 2) {
            // This should never happen, as there are at most 3 pieces of content to begin with and base has been
            // removed.
            throw new IllegalStateException("Unexpected amount of conflicting content: " + contents);
        }

        if (contents.size() != 1) {
            // there was new content both in left and right revisions that was not equal

            Iterator<Content<T,V>> it = contents.iterator();
            Content<T,V> first = it.next();
            Content<T,V> second = it.next();

            if (basePcsOpt.isPresent()) {
                return Optional.of(new Conflict<>(basePcsOpt.get(), first, second));
            } else { // TODO figure out why basePcs is not available in some cases
                return Optional.of(new Conflict<>(first, second));
            }
        }

        return Optional.empty();
    }

}
