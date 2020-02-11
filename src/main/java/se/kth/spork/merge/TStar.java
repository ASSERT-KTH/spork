package se.kth.spork.merge;

import com.github.gumtreediff.tree.ITree;

import java.util.*;

/**
 * Represents the T* set in 3DM merge.
 *
 * @author Simon Larsén
 */
public class TStar {
    private Map<ITree, Set<Pcs>> successors;
    private Map<ITree, Set<Pcs>> predecessors;
    private Map<ITree, Set<Content>> content;
    private Map<ITree, ITree> classRepMap;
    private Set<Pcs> star;

    // never add anything to this set!
    private final Set<Pcs> EMPTY_PCS_SET = new HashSet<>();

    /**
     * Create a T* from the provided trees, using the class representatives map to map each node to its class
     * representative.
     *
     * @param classRepMap A map mapping each node to its class representative.
     * @param trees The trees to add to this T*.
     */
    public TStar(Map<ITree, ITree> classRepMap, Set<Pcs>... trees) {
        this.classRepMap = classRepMap;
        successors = new HashMap<>();
        predecessors = new HashMap<>();
        content = new HashMap<>();
        star = new HashSet<>();
        Arrays.stream(trees).forEach(this::add);
    }

    /**
     * @return The current state of the merge.
     */
    public Set<Pcs> getStar() {
        return new HashSet<>(star);
    }

    /**
     * @return The current state of the merge's node contents.
     */
    public Map<ITree, Set<Content>> getContents() {
        return new HashMap<>(content);
    }

    /**
     * @param pcs A PCS triple.
     * @return Another PCS that matches the argument PCS on everything but root.
     */
    public Optional<Pcs> getOtherRoot(Pcs pcs) {
        return predecessors.getOrDefault(pcs.getPredecessor(), EMPTY_PCS_SET).stream()
                .filter(p -> p.getRoot() != pcs.getRoot()
                        && p.getSuccessor() == pcs.getSuccessor())
                .findFirst();
    }

    /**
     * @param pcs A PCS triple.
     * @return Another PCS that matches the argument PCS on everything but successor.
     */
    public Optional<Pcs> getOtherSuccessor(Pcs pcs) {
        return predecessors.getOrDefault(pcs.getPredecessor(), EMPTY_PCS_SET).stream()
                .filter(p -> p.getSuccessor() != pcs.getSuccessor() && p.getRoot() == pcs.getRoot())
                .findFirst();
    }

    /**
     * @param pcs A PCS triple.
     * @return Another PCS that matches the argument PCS on everything but predecessor.
     */
    public Optional<Pcs> getOtherPredecessor(Pcs pcs) {
        return successors.getOrDefault(pcs.getSuccessor(), EMPTY_PCS_SET).stream()
                .filter(p -> p.getPredecessor() != p.getPredecessor() && p.getRoot() == pcs.getRoot())
                .findFirst();
    }

    /**
     * @param pcs A PCS triple.
     * @return The content associated with the argument's predecessor node.
     */
    public Set<Content> getContent(Pcs pcs) {
        return content.get(pcs.getPredecessor());
    }

    /**
     * @param pcs A PCS triple.
     * @return true iff the argument is contained in this T*.
     */
    public boolean contains(Pcs pcs) {
        Set<Pcs> matches = predecessors.get(pcs.getPredecessor());
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
    public void remove(Pcs pcs) {
        ITree pred = pcs.getPredecessor();
        ITree succ = pcs.getPredecessor();

        if (pred != null)
            predecessors.get(pred).remove(pcs);
        if (succ != null)
            successors.get(succ).remove(pcs);

        star.remove(pcs);
    }

    /**
     * @param cont Content to remove from this T*.
     */
    public void remove(Content cont) {
        content.get(cont.getContext().getPredecessor()).remove(cont);
    }

    /**
     * Add a tree to this T*. This entails converting the entire PCS tree to its class representatives. Each
     * "class representative PCS" is then added to the predecessor and successor lookup tables, and their contents
     * are added to the content lookup table.
     *
     * @param tree
     */
    private void add(Set<Pcs> tree) {
        for (Pcs pcs : tree) {
            Pcs classRepPcs = addToStar(pcs);
            ITree pred = pcs.getPredecessor();
            ITree classRepPred = classRepPcs.getPredecessor();
            ITree classRepSucc = classRepPcs.getSuccessor();

            addToLookupTable(classRepPred, classRepPcs, predecessors);
            addToLookupTable(classRepSucc, classRepPcs, successors);
            if (pred != null) {
                Content c = new Content(pcs, pred.getLabel());
                addToLookupTable(classRepPred, c, content);
            }
        }
    }

    private Pcs addToStar(Pcs pcs) {
        ITree root = pcs.getRoot();
        ITree pred = pcs.getPredecessor();
        ITree succ = pcs.getSuccessor();
        Pcs classRepPcs = new Pcs(classRepMap.get(root), classRepMap.get(pred), classRepMap.get(succ));
        classRepPcs.setRevision(pcs.getRevision());
        star.add(classRepPcs);
        return classRepPcs;
    }

    private static <K, V> void addToLookupTable(K key, V val, Map<K, Set<V>> lookup) {
        if (key == null) return;

        Set<V> values = lookup.getOrDefault(key, new HashSet<V>());
        if (values.isEmpty())
            lookup.put(key, values);
        values.add(val);
    }
}
