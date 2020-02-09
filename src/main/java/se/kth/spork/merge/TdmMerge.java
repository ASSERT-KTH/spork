package se.kth.spork.merge;

import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.tree.ITree;

import java.util.*;

/**
 * An implementation of the 3DM merge algorithm by Tancred Lindholm. For details on 3DM merge, see the paper
 * <a href="https://doi.org/10.1145/1030397.1030399>A three-way merge for XML documents</a>.
 *
 * @author Simon Larsén
 */
public class TdmMerge {
    public static TStar merge(ITree base, ITree left, ITree right, Matcher matchLeft, Matcher matchRight) {
        Set<Pcs> t0 = Pcs.fromTree(base, Revision.BASE);
        Set<Pcs> t1 = Pcs.fromTree(left, Revision.LEFT);
        Set<Pcs> t2 = Pcs.fromTree(right, Revision.RIGHT);

        Map<ITree, ITree> classRepMap = initializeClassRepresentativesMap(base);
        mapToClassRepresentatives(left, matchLeft.getMappings(), classRepMap);
        mapToClassRepresentatives(right, matchRight.getMappings(), classRepMap);

        TStar delta = new TStar(classRepMap, t0, t1, t2);
        TStar t0Star = new TStar(classRepMap, t0);

        resolveRawMerge(t0Star, delta);

        return delta;
    }

    /**
     * Add the base tree's self-mappings to the class representatives map.
     */
    private static Map<ITree, ITree> initializeClassRepresentativesMap(ITree base) {
        Map<ITree, ITree> classRepMap = new HashMap<>();
        for (ITree tree : base.preOrder())
            classRepMap.put(tree, tree);
        return classRepMap;
    }


    /**
     * Map nodes in a revision to its class representative.
     */
    private static void mapToClassRepresentatives(ITree tree, MappingStore mappings, Map<ITree, ITree> classRepMap) {
        for (ITree t : tree.preOrder()) {
            if (mappings.hasDst(t)) {
                classRepMap.put(t, mappings.getSrc(t));
            } else {
                classRepMap.put(t, t);
            }
        }
    }

    /**
     * Attempt to resolve a raw merge by incrementally removing inconsistencies.
     *
     * @param base
     * @param delta
     */
    private static void resolveRawMerge(TStar base, TStar delta) {
        List<Conflict<Content>> contentConflicts = new ArrayList<>();
        List<Conflict<Pcs>> structuralConflicts = new ArrayList<>();

        for (Pcs pcs : delta.getStar()) {
            if (!delta.contains(pcs)) // was removed as otherPcs
                continue;

            Set<Content> contents = delta.getContent(pcs);
            if (contents.size() > 1) {
                handleContentConflict(contents, base).ifPresent(contentConflicts::add);
            }

            Optional<Pcs> other = delta.getOtherRoot(pcs);
            if (!other.isPresent())
                other = delta.getOtherPredecessor(pcs);
            if (!other.isPresent())
                other = delta.getOtherSuccessor(pcs);

            if (other.isPresent()) {
                Pcs otherPcs = other.get();

                if (base.contains(otherPcs)) {
                    delta.remove(otherPcs);
                } else if (base.contains(pcs)) {
                    delta.remove(pcs);
                } else {
                    structuralConflicts.add(new Conflict<>(pcs, otherPcs));
                }
            }
        }

        contentConflicts.forEach(System.out::println);
        structuralConflicts.forEach(System.out::println);
    }

    /**
     * Handle content conflicts, i.e. the same node is associated with multiple (potentially equivalent) contents.
     *
     * TODO have this method modify the contents in a less dirty way
     */
    private static Optional<Conflict<Content>> handleContentConflict(Set<Content> contents, TStar base) {
        if (contents.size() > 3)
            throw new IllegalArgumentException("expected at most 3 pieces of conflicting content, got: " + contents);

        // contents equal to that in base never takes precedence over left and right revisions
        Optional<Content> basePcsOpt = contents.stream().filter(base::contains).findAny();
        basePcsOpt.ifPresent(content -> contents.removeIf(c -> c.getValue().equals(content.getValue())));

        if (contents.size() == 0) { // everything was equal to base, re-add
            contents.add(basePcsOpt.get());
        } else if (contents.size() == 2) { // both left and right have been modified from base
            Iterator<Content> it = contents.iterator();
            Content first = it.next();
            Content second = it.next();
            if (second.getValue().equals(first.getValue()))
                it.remove();
        } else if (contents.size() > 2) {
            // This should never happen, as there are at most 3 pieces of content to begin with and base has been
            // removed.
            throw new IllegalStateException("Unexpected amount of conflicting content: " + contents);
        }

        if (contents.size() != 1) {
            // there was new content both in left and right revisions that was not equal

            // TODO find some way to retain information about which is left and right
            Iterator<Content> it = contents.iterator();
            Content first = it.next();
            Content second = it.next();

            if (basePcsOpt.isPresent()) {
                return Optional.of(new Conflict<>(basePcsOpt.get(), first, second));
            } else { // TODO figure out why basePcs is not available in some cases
                System.out.println("NOT PRESENT");
                return Optional.of(new Conflict<>(first, second));
            }
        }

        return Optional.empty();
    }

}
