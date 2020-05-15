package se.kth.spork.base3dm;

import se.kth.spork.util.LazyLogger;

import java.util.*;

/**
 * An implementation of the 3DM merge algorithm by Tancred Lindholm. For details on 3DM merge, see the paper
 * <a href="https://doi.org/10.1145/1030397.1030399">A three-way merge for XML documents</a>.
 *
 * @author Simon Larsén
 */
public class TdmMerge {
    public static final String REV = "rev";

    private static final LazyLogger LOGGER = new LazyLogger(TdmMerge.class);

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
    public static <T extends ListNode,V> void resolveRawMerge(ChangeSet<T,V> base, ChangeSet<T,V> delta) {
        for (Pcs<T> pcs : delta.getPcsSet()) {
            if (!delta.contains(pcs)) // was removed as otherPcs
                continue;

            // We need to merge the content of the predecessor and successor, but we can skip the parent.
            // The reason is that a parent node that never appears as a predecessor or successor will never be
            // processed when converting from PCS to tree, with the exception of the virtual root (which has no content).
            // It is however possible for a node to only appear as predecessor or successor in certain conflict
            // situations, see https://github.com/kth/spork/issues/82 for details
            mergeContent(pcs.getPredecessor(), base, delta);
            mergeContent(pcs.getSuccessor(), base, delta);

            List<Pcs<T>> others = delta.getOtherRoots(pcs);
            others.addAll(delta.getOtherPredecessors(pcs));
            others.addAll(delta.getOtherSuccessors(pcs));

            for (Pcs<T> otherPcs : others) {
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
        if (!structuralConflicts.isEmpty()) {
            LOGGER.warn(() -> "STRUCTURAL CONFLICTS DETECTED: " + structuralConflicts);
        }
    }

    /**
     * Merge the content of a node, if possible.
     */
    private static <T extends ListNode,V> void mergeContent(T node, ChangeSet<T,V> base, ChangeSet<T,V> delta) {
        Set<Content<T,V>> contents = delta.getContent(node);
        if (contents != null && contents.size() > 1) {
            Set<Content<T,V>> newContent = handleContentConflict(contents, base);
            delta.setContent(node, newContent);
        }
    }

    /**
     * Handle content conflicts, i.e. the same node is associated with multiple (potentially equivalent) contents.
     *
     * If the conflict can be automatically resolved, the new contents (with only one piece of content) are returned.
     *
     * If the content conflict cannot be automatically resolved, the contents argument is simply returned as-is.
     */
    private static <T extends ListNode,V> Set<Content<T,V>> handleContentConflict(Set<Content<T,V>> contents, ChangeSet<T,V> base) {
        if (contents.size() > 3)
            throw new IllegalArgumentException("expected at most 3 pieces of conflicting content, got: " + contents);

        Set<Content<T,V>> newContent = new HashSet<>(contents);

        // contents equal to that in base never takes precedence over left and right revisions
        Optional<Content<T,V>> basePcsOpt = contents.stream().filter(base::contains).findAny();

        basePcsOpt.ifPresent(content -> newContent.removeIf(c -> Objects.equals(c.getValue(), content.getValue())));

        if (newContent.size() == 0) { // everything was equal to base, re-add
            newContent.add(basePcsOpt.get());
        } else if (newContent.size() == 2) { // both left and right have been modified from base
            Iterator<Content<T,V>> it = newContent.iterator();
            Content<T,V> first = it.next();
            Content<T,V> second = it.next();
            if (second.getValue().equals(first.getValue()))
                it.remove();
        } else if (newContent.size() > 2) {
            // This should never happen, as there are at most 3 pieces of content to begin with and base has been
            // removed.
            throw new IllegalStateException("Unexpected amount of conflicting content: " + newContent);
        }

        if (newContent.size() != 1) {
            // conflict could not be resolved

            Iterator<Content<T,V>> it = newContent.iterator();
            Content<T,V> first = it.next();
            Content<T,V> second = it.next();

            LOGGER.warn(() -> "Content conflict: " + first + ", " + second);

            // the reason all content is returned is that further processing of conflicts may be done after the base
            // 3DM merge has finished, which may require the content of all revisions
            return contents;
        }

        return newContent;
    }

}
