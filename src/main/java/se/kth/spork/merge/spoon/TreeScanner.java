package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Pcs;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.*;

public class TreeScanner extends CtScanner {
    private Map<Long, CtElement> rootTolastSibling = new HashMap<>();
    private IdentifierSupport idSup = IdentifierSupport.getInstance();
    private Set<Pcs<CtElement>> pcses = new HashSet<>();
    private CtElement root = null;

    @Override
    protected void enter(CtElement e) {
        if (root == null)
            root = e;

        CtElement parent = e.getParent();
        Long parentKey = idSup.getKey(parent);
        CtElement predecessor = rootTolastSibling.get(parentKey);
        pcses.add(newPcs(parent,predecessor, e));
        rootTolastSibling.put(parentKey, e);
    }

    @Override
    protected void exit(CtElement e) {
        if (e == root) {
            finalizePcsLists();
        }
    }

    private static Pcs<CtElement> newPcs(CtElement root, CtElement predecessor, CtElement successor) {
        return new Pcs<>(root, predecessor, successor, elem -> {
            String longRep = elem.toString();
            if (longRep.contains("\n")) {
                String[] shortRep = elem.getShortRepresentation().split("\\.");
                return shortRep[shortRep.length - 1];
            }
            return longRep;
        }, System::identityHashCode);
    }
    /**
     * Add the last element to each PCS list (i.e. Pcs(root, child, null))
     */
    private void finalizePcsLists() {
        // add the fake root
        pcses.add(newPcs(null, root, null));
        pcses.add(newPcs(null, null, root));


        // add the end-of-list to each PCS list
        for (CtElement predecessor : rootTolastSibling.values()) {
            pcses.add(newPcs(predecessor.getParent(), predecessor, null));
        }
    }

    public Set<Pcs<CtElement>> getPcses() {
        return pcses;
    }
}
