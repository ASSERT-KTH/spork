package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.*;

public class TreeScanner extends CtScanner {
    private Map<CtWrapper, CtWrapper> rootTolastSibling = new HashMap<>();
    private Set<Pcs<CtWrapper>> pcses = new HashSet<>();
    private CtWrapper root = null;
    private Revision revision;

    public TreeScanner(Revision revision) {
       super();
       this.revision = revision;
    }

    @Override
    protected void enter(CtElement e) {
        CtWrapper wrapped = WrapperFactory.wrap(e);
        if (root == null)
            root = wrapped;

        CtWrapper parent = WrapperFactory.wrap(e.getParent());
        CtWrapper predecessor = rootTolastSibling.get(parent);
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
     * Add the last element to each PCS list (i.e. Pcs(root, child, null))
     */
    private void finalizePcsLists() {
        // add the end-of-list to each PCS list
        for (CtWrapper predecessor : rootTolastSibling.values()) {
            pcses.add(newPcs(predecessor.getParent(), predecessor, null, revision));
        }
    }

    public Set<Pcs<CtWrapper>> getPcses() {
        return pcses;
    }

    private static Pcs<CtWrapper> newPcs(CtWrapper root, CtWrapper predecessor, CtWrapper successor, Revision revision) {
        Pcs<CtWrapper> pcs = new Pcs(root, predecessor, successor);
        pcs.setRevision(revision);
        return pcs;
    }
}
