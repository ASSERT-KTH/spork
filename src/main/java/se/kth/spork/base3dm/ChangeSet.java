package se.kth.spork.base3dm;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a change set in 3DM merge. While a change set in pure 3DM merge is just all content tuples and PCS
 * triples put together, this change set separates content and structure into separate sets, and also introduces
 * some helper functionality to keep track of conflicts and enable faster lookup of PCS triples.
 *
 * @author Simon Larsén
 */
public class ChangeSet<T extends ListNode,V> {
    private Map<T, Set<Pcs<T>>> successors;
    private Map<T, Set<Pcs<T>>> predecessors;
    private Map<T, Set<Content<T,V>>> content;
    private Map<T, T> classRepMap;
    private Set<Pcs<T>> pcsSet;
    private Map<Pcs<T>, Set<Pcs<T>>> structuralConflicts;

    @SuppressWarnings("unchecked")
    private final Set<Pcs<T>> EMPTY_PCS_SET = Collections.EMPTY_SET;

    @SuppressWarnings("unchecked")
    private final Set<Content<T,V>> EMPTY_CONTENT_SET = Collections.EMPTY_SET;

    /**
     * Create a T* from the provided trees, using the class representatives map to map each node to its class
     * representative.
     *
     * @param classRepMap A map mapping each node to its class representative.
     * @param getContent A function for getting content from
     * @param trees The trees to add to this T*.
     */
    @SafeVarargs
    public ChangeSet(Map<T, T> classRepMap, Function<T, V> getContent, Set<Pcs<T>>... trees) {
        this.classRepMap = classRepMap;
        successors = new HashMap<>();
        predecessors = new HashMap<>();
        content = new HashMap<>();
        pcsSet = new HashSet<>();
        Arrays.stream(trees).forEach(t -> add(t, getContent));
        structuralConflicts = new HashMap<>();
    }

    /**
     * @return The current state of the structure of the merge.
     */
    public Set<Pcs<T>> getPcsSet() {
        return new HashSet<>(pcsSet);
    }

    /**
     * @return The current state of the merge's node contents.
     */
    public Map<T, Set<Content<T,V>>> getContents() {
        return new HashMap<>(content);
    }

    /**
     * @param pcs A PCS triple.
     * @return All PCSes that are root conflicting with the provided PCS.
     */
    public List<Pcs<T>> getOtherRoots(Pcs<T> pcs) {
        return Stream.of(pcs.getPredecessor(), pcs.getSuccessor()).flatMap(
                node -> Stream.of(predecessors.get(node), successors.get(node))
                        .filter(Objects::nonNull)
                        .flatMap(Set::stream)
        ).filter(p -> !p.getRoot().equals(pcs.getRoot())).collect(Collectors.toList());
    }

    /**
     * @param pcs A PCS triple.
     * @return All PCSes that are successor conflicting with the provided PCS.
     */
    public List<Pcs<T>> getOtherSuccessors(Pcs<T> pcs) {
        return predecessors.getOrDefault(pcs.getPredecessor(), EMPTY_PCS_SET).stream()
                .filter(p -> !p.getSuccessor().equals(pcs.getSuccessor()))
                .collect(Collectors.toList());
    }

    /**
     * @param pcs A PCS triple.
     * @return All PCSes that are predecessor conflicting with the provided PCS.
     */
    public List<Pcs<T>> getOtherPredecessors(Pcs<T> pcs) {
        return successors.getOrDefault(pcs.getSuccessor(), EMPTY_PCS_SET).stream()
                .filter(p -> !p.getPredecessor().equals(pcs.getPredecessor()))
                .collect(Collectors.toList());
    }

    /**
     * @param node A node..
     * @return The content associated with the argument node, or an empty set if no content was associated.
     */
    public Set<Content<T,V>> getContent(T node) {
        return Collections.unmodifiableSet(content.getOrDefault(node, EMPTY_CONTENT_SET));
    }

    /**
     * @param pcs A PCS triple.
     * @return true iff the argument is contained in this T*.
     */
    public boolean contains(Pcs<T> pcs) {
        Set<Pcs<T>> matches = predecessors.get(pcs.getPredecessor());
        return matches != null && matches.contains(pcs);
    }

    /**
     * @param cont A Content container.
     * @return true iff the argument is contained in this T*.
     */
    public boolean contains(Content<T,V> cont) {
        return content.getOrDefault(cont.getContext().getPredecessor(), new HashSet<>()).contains(cont);
    }

    /**
     * Set the content for some pcs triple, overwriting anything that was there previously.
     *
     * @param node A node to associate the content with. This is the key in the backing map.
     * @param nodeContents A set of content values to associate with the node.
     */
    public void setContent(T node, Set<Content<T, V>> nodeContents) {
        content.put(node, nodeContents);
    }

    /**
     * Remove the PCS from all lookup tables, except for the contents table. Also remove it from the *-set.
     *
     * @param pcs A PCS triple.
     */
    public void remove(Pcs<T> pcs) {
        T pred = pcs.getPredecessor();
        T succ = pcs.getSuccessor();

        predecessors.get(pred).remove(pcs);
        successors.get(succ).remove(pcs);

        pcsSet.remove(pcs);
    }

    /**
     * @param cont Content to remove from this T*.
     */
    public void remove(Content<T,V> cont) {
        content.get(cont.getContext().getPredecessor()).remove(cont);
    }

    /**
     * Register a conflict in a bi-directional lookup table.
     *
     * @param first A PCS triple that conflicts with second.
     * @param second A PCS triple that conflicts with first.
     */
    public void registerStructuralConflict(Pcs<T> first, Pcs<T> second) {
        addToLookupTable(first, second, structuralConflicts);
        addToLookupTable(second, first, structuralConflicts);
    }

    /**
     * @return A copy of the internal structural conflicts lookup table.
     */
    public Map<Pcs<T>, Set<Pcs<T>>> getStructuralConflicts() {
        return new HashMap<>(structuralConflicts);
    }

    /**
     * Check if a PCS is involved in a structural conflict.
     *
     * @param pcs A PCS triple.
     * @return true iff the argument is involved in a structural conflict.
     */
    public boolean inStructuralConflict(Pcs<T> pcs) {
        return structuralConflicts.containsKey(pcs);
    }

    /**
     * Add a tree to this T*. This entails converting the entire PCS tree to its class representatives. Each
     * "class representative PCS" is then added to the predecessor and successor lookup tables, and their contents
     * are added to the content lookup table.
     *
     * @param tree A PCS tree structure.
     * @param getContent A function that returns the content of a T node.
     */
    private void add(Set<Pcs<T>> tree, Function<T, V> getContent) {
        for (Pcs<T> pcs : tree) {
            Pcs<T> classRepPcs = addToStar(pcs);
            T pred = pcs.getPredecessor();
            T classRepPred = classRepPcs.getPredecessor();
            T classRepSucc = classRepPcs.getSuccessor();

            if (classRepPred != null || classRepSucc != null) { // don't map leaf nodes
                addToLookupTable(classRepPred, classRepPcs, predecessors);
                addToLookupTable(classRepSucc, classRepPcs, successors);
            }
            if (!pred.isListEdge()) {
                Content<T,V> c = new Content<T,V>(pcs, getContent.apply(pred));
                addToLookupTable(classRepPred, c, content);
            }
        }
    }

    private Pcs<T> addToStar(Pcs<T> pcs) {
        T root = pcs.getRoot();
        T pred = pcs.getPredecessor();
        T succ = pcs.getSuccessor();
        Pcs<T> classRepPcs = new Pcs<T>(classRepMap.get(root), classRepMap.get(pred), classRepMap.get(succ), pcs.getRevision());
        pcsSet.add(classRepPcs);
        return classRepPcs;
    }

    private static <K, V> void addToLookupTable(K key, V val, Map<K, Set<V>> lookup) {
        Set<V> values = lookup.getOrDefault(key, new HashSet<V>());
        if (values.isEmpty())
            lookup.put(key, values);
        values.add(val);
    }
}
