package se.kth.spork.merge;

import spoon.reflect.declaration.CtModule;

import java.util.*;

/**
 * An implementation of the 3DM merge algorithm by Tancred Lindholm. For details on 3DM merge, see the paper
 * <a href="https://doi.org/10.1145/1030397.1030399>A three-way merge for XML documents</a>.
 *
 * @author Simon Larsén
 */
public class TdmMerge {
    public static final String REV = "rev";

    /**
     * Attempt to resolve a raw merge by incrementally removing inconsistencies.
     *
     * @param base
     * @param delta
     */
    public static <T> void resolveRawMerge(TStar<T> base, TStar<T> delta) {
        List<Conflict<Content<T>>> contentConflicts = new ArrayList<>();
        List<Conflict<Pcs<T>>> structuralConflicts = new ArrayList<>();

        for (Pcs<T> pcs : delta.getStar()) {
            if (!delta.contains(pcs)) // was removed as otherPcs
                continue;

            if (pcs.getPredecessor() != null) {
                Set<Content<T>> contents = delta.getContent(pcs);
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
                    structuralConflicts.add(new Conflict<>(pcs, otherPcs));
                }
            }
        }


        if (!contentConflicts.isEmpty()) {
            throw new IllegalStateException("CONTENT CONFLICTS DETECTED: " + contentConflicts);
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
    private static <T> Optional<Conflict<Content<T>>> handleContentConflict(Set<Content<T>> contents, TStar<T> base) {
        if (contents.size() > 3)
            throw new IllegalArgumentException("expected at most 3 pieces of conflicting content, got: " + contents);

        // contents equal to that in base never takes precedence over left and right revisions
        Optional<Content<T>> basePcsOpt = contents.stream().filter(base::contains).findAny();

        basePcsOpt.ifPresent(content -> contents.removeIf(c -> Objects.equals(c.getValue(), content.getValue())));

        if (contents.size() == 0) { // everything was equal to base, re-add
            contents.add(basePcsOpt.get());
        } else if (contents.size() == 2) { // both left and right have been modified from base
            Iterator<Content<T>> it = contents.iterator();
            Content<T> first = it.next();
            Content<T> second = it.next();
            if (second.getValue().equals(first.getValue()))
                it.remove();
        } else if (contents.size() > 2) {
            // This should never happen, as there are at most 3 pieces of content to begin with and base has been
            // removed.
            throw new IllegalStateException("Unexpected amount of conflicting content: " + contents);
        }

        if (contents.size() != 1) {
            // there was new content both in left and right revisions that was not equal

            Iterator<Content<T>> it = contents.iterator();
            Content<T> first = it.next();
            Content<T> second = it.next();

            if (basePcsOpt.isPresent()) {
                return Optional.of(new Conflict<>(basePcsOpt.get(), first, second));
            } else { // TODO figure out why basePcs is not available in some cases
                return Optional.of(new Conflict<>(first, second));
            }
        }

        return Optional.empty();
    }

}
