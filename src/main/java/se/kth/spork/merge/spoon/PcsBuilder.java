package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.*;

/**
 * A scanner that builds a PCS structure from a Spoon tree.
 *
 * @author Simon Larsén
 */
class PcsBuilder extends CtScanner {
    private Map<SpoonNode, SpoonNode> rootTolastSibling = new HashMap<>();
    private Set<Pcs<SpoonNode>> pcses = new HashSet<>();
    private SpoonNode root = null;
    private Revision revision;

    public PcsBuilder(Revision revision) {
       super();
       this.revision = revision;
    }

    @Override
    protected void enter(CtElement e) {
        SpoonNode wrapped = NodeFactory.wrap(e);
        if (root == null)
            root = wrapped;

        SpoonNode parent = wrapped.getParent();
        SpoonNode predecessor = rootTolastSibling.getOrDefault(parent, NodeFactory.startOfChildList(wrapped.getParent()));
        pcses.add(newPcs(parent, predecessor, wrapped, revision));
        rootTolastSibling.put(parent, wrapped);
    }

    @Override
    protected void exit(CtElement e) {
        if (e == root.getElement()) {
            finalizePcsLists();
        }
    }

    /**
     * Add the last element to each PCS list (i.e. Pcs(root, child, null)).
     */
    private void finalizePcsLists() {
        for (SpoonNode predecessor : rootTolastSibling.values()) {
            pcses.add(newPcs(predecessor.getParent(), predecessor, NodeFactory.endOfChildList(predecessor.getParent()), revision));
        }
    }

    public Set<Pcs<SpoonNode>> getPcses() {
        return pcses;
    }

    private static Pcs<SpoonNode> newPcs(SpoonNode root, SpoonNode predecessor, SpoonNode successor, Revision revision) {
        Pcs<SpoonNode> pcs = new Pcs<>(root, predecessor, successor);
        pcs.setRevision(revision);
        return pcs;
    }
}
