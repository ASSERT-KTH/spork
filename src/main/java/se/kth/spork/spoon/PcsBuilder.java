package se.kth.spork.spoon;

import java.util.*;
import se.kth.spork.base3dm.Pcs;
import se.kth.spork.base3dm.Revision;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.SpoonNode;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

/** A scanner that builds a PCS structure from a Spoon tree. */
class PcsBuilder extends CtScanner {
    private Map<SpoonNode, SpoonNode> parentToLastSibling = new HashMap<>();
    private Set<Pcs<SpoonNode>> pcses = new HashSet<>();
    private SpoonNode root = null;
    private Revision revision;

    public PcsBuilder(Revision revision) {
        super();
        this.revision = revision;
        SpoonNode root = NodeFactory.INSTANCE.getVirtualRoot();
        parentToLastSibling.put(root, root.getStartOfChildList());
    }

    /**
     * Convert a Spoon tree into a PCS structure.
     *
     * @param spoonClass A Spoon class.
     * @param revision The revision this Spoon class belongs to. The revision is attached to each
     *     PCS triple.
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
        SpoonNode wrapped = NodeFactory.wrap(e);
        SpoonNode parent = wrapped.getParent();

        if (root == null) root = wrapped;

        parentToLastSibling.put(wrapped, wrapped.getStartOfChildList());

        SpoonNode predecessor =
                parentToLastSibling.getOrDefault(parent, parent.getStartOfChildList());
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
                pcses.add(new Pcs<>(parent, lastSibling, parent.getEndOfChildList(), revision));
            } else {
                // this is a concrete node, we must add all of its virtual children to the PCS
                // structure, except for
                // the start of the child list as it has all ready been added
                List<SpoonNode> virtualNodes = parent.getVirtualNodes();
                SpoonNode pred = lastSibling;
                for (SpoonNode succ : virtualNodes.subList(1, virtualNodes.size())) {
                    pcses.add(new Pcs<>(parent, pred, succ, revision));

                    // also need to create "leaf child lists" for any non-list-edge virtual node
                    // that does not have any
                    // children, or Spork will not discover removals that entirely empty the child
                    // list.
                    // The problem is detailed in https://github.com/KTH/spork/issues/116
                    if (!pred.isListEdge() && !parentToLastSibling.containsKey(pred)) {
                        pcses.add(createLeafPcs(pred));
                    }

                    pred = succ;
                }
            }
        }
    }

    private Pcs<SpoonNode> createLeafPcs(SpoonNode node) {
        return new Pcs<>(node, node.getStartOfChildList(), node.getEndOfChildList(), revision);
    }

    public Set<Pcs<SpoonNode>> getPcses() {
        return pcses;
    }
}
