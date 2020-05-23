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
    private Map<SpoonNode, SpoonNode> parentToLastSibling = new HashMap<>();
    private Set<Pcs<SpoonNode>> pcses = new HashSet<>();
    private SpoonNode root = null;
    private Revision revision;

    public PcsBuilder(Revision revision) {
       super();
       this.revision = revision;
       parentToLastSibling.put(NodeFactory.ROOT, NodeFactory.startOfChildList(NodeFactory.ROOT));
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
        scanner.finishPcses();
        return scanner.getPcses();
    }

    @Override
    protected void enter(CtElement e) {
        SpoonNode wrapped;
        SpoonNode parent;

        if (root == null) {
            parent = NodeFactory.ROOT;
            wrapped = NodeFactory.initializeWrapper(e, parent);
            root = wrapped;
        } else {
            NodeFactory.Node actualParent = NodeFactory.wrap(e.getParent());
            wrapped = NodeFactory.initializeRoledWrapper(e, actualParent);
            parent = wrapped.getParent();
        }

        parentToLastSibling.put(wrapped, NodeFactory.startOfChildList(wrapped));

        SpoonNode predecessor = parentToLastSibling.getOrDefault(parent, NodeFactory.startOfChildList(parent));
        pcses.add(new Pcs<>(parent, predecessor, wrapped, revision));
        parentToLastSibling.put(parent, wrapped);
    }

    private void finishPcses() {
        for (Map.Entry<SpoonNode, SpoonNode> nodePair : parentToLastSibling.entrySet()) {
            SpoonNode parent = nodePair.getKey();
            SpoonNode lastSibling = nodePair.getValue();
            if (parent.isVirtual()) {
                // this is either the virtual root, or a RoleNode
                // we just need to close their child lists
                pcses.add(new Pcs<>(parent, lastSibling, NodeFactory.endOfChildList(parent), revision));
            } else {
                // this is a concrete node, we must add all of its virtual children to the PCS structure
                List<SpoonNode> virtualNodes = parent.getVirtualNodes();
                assert lastSibling.isStartOfList();
                assert virtualNodes.get(0).equals(lastSibling);
                SpoonNode pred = lastSibling;
                for (SpoonNode succ : virtualNodes.subList(1, virtualNodes.size())) {
                    pcses.add(new Pcs<>(parent, pred, succ, revision));
                    pred = succ;
                }
            }
        }
    }

    public Set<Pcs<SpoonNode>> getPcses() {
        return pcses;
    }
}
