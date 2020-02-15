package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Pcs;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.*;

public class TreeScanner extends CtScanner {
    private Map<CtWrapper, CtWrapper> rootTolastSibling = new HashMap<>();
    private Set<Pcs<CtWrapper>> pcses = new HashSet<>();
    private CtWrapper root = null;

    @Override
    protected void enter(CtElement e) {
        CtWrapper wrapped = WrapperFactory.wrap(e);
        if (root == null)
            root = wrapped;

        CtWrapper parent = WrapperFactory.wrap(e.getParent());
        CtWrapper predecessor = rootTolastSibling.get(parent);
        pcses.add(new Pcs(parent, predecessor, wrapped));
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
        // add the fake root
        pcses.add(new Pcs<>(null, root, null));
        pcses.add(new Pcs<>(null, null, root));


        // add the end-of-list to each PCS list
        for (CtWrapper predecessor : rootTolastSibling.values()) {
            pcses.add(new Pcs<>(predecessor.getParent(), predecessor, null));
        }
    }

    public Set<Pcs<CtWrapper>> getPcses() {
        return pcses;
    }
}
