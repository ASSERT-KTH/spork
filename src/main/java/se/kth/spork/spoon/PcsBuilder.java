package se.kth.spork.spoon;

import se.kth.spork.base3dm.Pcs;
import se.kth.spork.base3dm.Revision;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.SpoonNode;
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
       rootTolastSibling.put(NodeFactory.ROOT, NodeFactory.startOfChildList(NodeFactory.ROOT));
    }

    /**
     * Convert a Spoon tree into a PCS structure.
     *
     * @param spoonClass A Spoon class.
     * @param revision   The revision this Spoon class belongs to. The revision is attached to each PCS triple.
     * @return The Spoon tree represented by PCS triples.
     */
    public static Set<Pcs<SpoonNode>> fromSpoon(CtElement spoonClass, Revision revision) {
        PcsBuilder scanner = new PcsBuilder(revision);
        scanner.scan(spoonClass);
        return scanner.getPcses();
    }

    @Override
    protected void enter(CtElement e) {
        SpoonNode wrapped = NodeFactory.wrap(e);
        if (root == null)
            root = wrapped;

        rootTolastSibling.put(wrapped, NodeFactory.startOfChildList(wrapped));

        SpoonNode parent = wrapped.getParent();
        SpoonNode predecessor = rootTolastSibling.get(parent);
        pcses.add(new Pcs<>(parent, predecessor, wrapped, revision));
        rootTolastSibling.put(parent, wrapped);
    }

    @Override
    protected void exit(CtElement e) {
        SpoonNode current = NodeFactory.wrap(e);
        SpoonNode predecessor = rootTolastSibling.get(current);
        SpoonNode successor = NodeFactory.endOfChildList(current);
        pcses.add(new Pcs<>(current, predecessor, successor, revision));

        if (current.getParent() == NodeFactory.ROOT) {
            // need to finish the virtual root's child list artificially as it is not a real node
            pcses.add(new Pcs<>(NodeFactory.ROOT, current, NodeFactory.endOfChildList(NodeFactory.ROOT), revision));
        }
    }

    public Set<Pcs<SpoonNode>> getPcses() {
        return pcses;
    }
}
