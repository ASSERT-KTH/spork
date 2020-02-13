package se.kth.spork.merge;

import com.github.gumtreediff.tree.ITree;

import java.util.*;
import java.util.function.Function;

/**
 * Represents the T* set in 3DM merge.
 *
 * @author Simon Larsén
 */
public class TStar<T> {
    private Map<T, Set<Pcs<T>>> successors;
    private Map<T, Set<Pcs<T>>> predecessors;
    private Map<T, Set<Content<T>>> content;
    private Map<T, T> classRepMap;
    private Set<Pcs<T>> star;

    // never add anything to this set!
    private final Set<Pcs<T>> EMPTY_PCS_SET = new HashSet<>();

    /**
     * Create a T* from the provided trees, using the class representatives map to map each node to its class
     * representative.
     *
     * @param classRepMap A map mapping each node to its class representative.
     * @param getContent A function for getting content from
     * @param trees The trees to add to this T*.
     */
    public TStar(Map<T, T> classRepMap, Function<T, ?> getContent, Set<Pcs<T>>... trees) {
        this.classRepMap = classRepMap;
        successors = new HashMap<>();
        predecessors = new HashMap<>();
        content = new HashMap<>();
        star = new HashSet<>();
        Arrays.stream(trees).forEach(t -> add(t, getContent));
    }

    /**
     * @return The current state of the merge.
     */
    public Set<Pcs<T>> getStar() {
        return new HashSet<>(star);
    }

    /**
     * @return The current state of the merge's node contents.
     */
    public Map<T, Set<Content<T>>> getContents() {
        return new HashMap<>(content);
    }

    /**
     * @param pcs A PCS triple.
     * @return Another PCS that matches the argument PCS on everything but root.
     */
    public Optional<Pcs<T>> getOtherRoot(Pcs<T> pcs) {
        return predecessors.getOrDefault(pcs.getPredecessor(), EMPTY_PCS_SET).stream()
                .filter(p -> p.getRoot() != pcs.getRoot()
                        && p.getSuccessor() == pcs.getSuccessor())
                .findFirst();
    }

    /**
     * @param pcs A PCS triple.
     * @return Another PCS that matches the argument PCS on everything but successor.
     */
    public Optional<Pcs<T>> getOtherSuccessor(Pcs<T> pcs) {
        return predecessors.getOrDefault(pcs.getPredecessor(), EMPTY_PCS_SET).stream()
                .filter(p -> p.getSuccessor() != pcs.getSuccessor() && p.getRoot() == pcs.getRoot())
                .findFirst();
    }

    /**
     * @param pcs A PCS triple.
     * @return Another PCS that matches the argument PCS on everything but predecessor.
     */
    public Optional<Pcs<T>> getOtherPredecessor(Pcs<T> pcs) {
        return successors.getOrDefault(pcs.getSuccessor(), EMPTY_PCS_SET).stream()
                .filter(p -> p.getPredecessor() != p.getPredecessor() && p.getRoot() == pcs.getRoot())
                .findFirst();
    }

    /**
     * @param pcs A PCS triple.
     * @return The content associated with the argument's predecessor node.
     */
    public Set<Content<T>> getContent(Pcs<T> pcs) {
        return content.get(pcs.getPredecessor());
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
    public boolean contains(Content cont) {
        return content.getOrDefault(cont.getContext().getPredecessor(), new HashSet<>()).contains(cont);
    }


    /**
     * Remove the PCS from all lookup tables, except for the contents table. Also remove it from the *-set.
     *
     * @param pcs A PCS triple.
     */
    public void remove(Pcs<T> pcs) {
        T pred = pcs.getPredecessor();
        T succ = pcs.getPredecessor();

        predecessors.get(pred).remove(pcs);
        successors.get(succ).remove(pcs);

        star.remove(pcs);
    }

    /**
     * @param cont Content to remove from this T*.
     */
    public void remove(Content<T> cont) {
        content.get(cont.getContext().getPredecessor()).remove(cont);
    }

    /**
     * Add a tree to this T*. This entails converting the entire PCS tree to its class representatives. Each
     * "class representative PCS" is then added to the predecessor and successor lookup tables, and their contents
     * are added to the content lookup table.
     *
     * @param tree A PCS tree structure.
     * @param getContent A function that returns the content of a T node.
     */
    private void add(Set<Pcs<T>> tree, Function<T, ?> getContent) {
        for (Pcs<T> pcs : tree) {
            Pcs<T> classRepPcs = addToStar(pcs);
            T pred = pcs.getPredecessor();
            T classRepPred = classRepPcs.getPredecessor();
            T classRepSucc = classRepPcs.getSuccessor();

            if (classRepPred != null || classRepSucc != null) { // don't map leaf nodes
                addToLookupTable(classRepPred, classRepPcs, predecessors);
                addToLookupTable(classRepSucc, classRepPcs, successors);
            }
            if (pred != null) {
                Content<T> c = new Content<T>(pcs, getContent.apply(pred));
                addToLookupTable(classRepPred, c, content);
            }
        }
    }

    private Pcs<T> addToStar(Pcs<T> pcs) {
        T root = pcs.getRoot();
        T pred = pcs.getPredecessor();
        T succ = pcs.getSuccessor();
        Pcs<T> classRepPcs = new Pcs<T>(classRepMap.get(root), classRepMap.get(pred), classRepMap.get(succ));
        classRepPcs.setRevision(pcs.getRevision());
        star.add(classRepPcs);
        return classRepPcs;
    }

    private static <K, V> void addToLookupTable(K key, V val, Map<K, Set<V>> lookup) {
        Set<V> values = lookup.getOrDefault(key, new HashSet<V>());
        if (values.isEmpty())
            lookup.put(key, values);
        values.add(val);
    }
}
