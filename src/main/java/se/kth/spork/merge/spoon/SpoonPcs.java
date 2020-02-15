package se.kth.spork.merge.spoon;

import com.github.gumtreediff.tree.ITree;
import com.github.gumtreediff.tree.Tree;
import se.kth.spork.merge.Content;
import se.kth.spork.merge.Pcs;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Class for going from a Spoon tree to a PCS structure.
 *
 * @author Simon Larsén
 */
public class SpoonPcs {

    public static Set<Pcs<CtWrapper>> fromSpoonWithScanner(CtClass<?> spoonClass) {
        TreeScanner scanner = new TreeScanner();
        scanner.scan(spoonClass);
        return scanner.getPcses();
    }

    public static CtClass<?> fromPcs(Set<Pcs<CtWrapper>> pcses) {
        Builder builder = new Builder();
        traversePcs(pcses, builder);
        return builder.actualRoot;
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
     * Visits each node twice. In the first visit, the node is a child of the previously visited root, and in the
     * second visit, it is the root of the current subtree.
     *
     * Consequently, the first node(s) to be visited are children of a virtual root node.
     *
     * @param pcses A well-formed PCS structure.
     * @param visit A function to apply to the nodes in the PCS structure.
     */
    private static <V> void traversePcs(Set<Pcs<V>> pcses, Consumer<V> visit) {
        Map<V, Map<V, Pcs<V>>> rootToChildren = new HashMap<>();
        for (Pcs<V> pcs : pcses) {
            Map<V, Pcs<V>> children = rootToChildren.getOrDefault(pcs.getRoot(), new HashMap<>());
            if (children.isEmpty()) rootToChildren.put(pcs.getRoot(), children);

            children.put(pcs.getPredecessor(), pcs);
        }

        traversePcs(rootToChildren, null, visit);
    }

    private static <K,V> void traversePcs(Map<V, Map<V, Pcs<V>>> rootToChildren, V currentRoot, Consumer<V> visit) {
        if (currentRoot != null)
            visit.accept(currentRoot);
        Map<V, Pcs<V>> children = rootToChildren.get(currentRoot);
        if (children == null) // leaf node
            return;

        V pred = null;
        List<V> sortedChildren = new ArrayList<>();
        while (true) {
            Pcs<V> nextPcs = children.get(pred);
            pred = nextPcs.getSuccessor();
            if (pred == null) {
                break;
            }
            sortedChildren.add(pred);
            visit.accept(pred);
        };
        sortedChildren.forEach(child -> traversePcs(rootToChildren, child, visit));
    }

    private static class Builder implements Consumer<CtWrapper> {
        private CtElement currentRoot;
        private CtClass<?> actualRoot;
        private Map<CtWrapper, CtWrapper> nodes;

        private Builder() {
            nodes = new HashMap<>();
        }

        @Override
        public void accept(CtWrapper treeWrapper) {
            CtElement tree = treeWrapper.getElement();
            CtWrapper treeCopyWrapper = nodes.get(treeWrapper);
            CtElement treeCopy = treeCopyWrapper == null ? null : treeCopyWrapper.getElement();

            if (treeCopy == null) { // first time we see this node; it's a child node of the current root

                treeCopy = copyTree(tree, currentRoot);

                if (currentRoot != null) {
                    CtRole childRole = tree.getRoleInParent();

                    Object current = currentRoot.getValueByRole(childRole);
                    Object toSet;

                    if (current instanceof Collection) {
                        Collection<CtElement> mutableCurrent;
                        if (current instanceof Set) {
                            mutableCurrent = new HashSet<>((Collection) current);
                        } else if (current instanceof List) {
                            mutableCurrent = new ArrayList<>((Collection) current);
                        } else {
                            throw new IllegalStateException("unexpected value by role: " + current.getClass());
                        }
                        mutableCurrent.add(treeCopy);
                        toSet = mutableCurrent;
                    } else {
                        toSet = treeCopy;
                    }

                    currentRoot.setValueByRole(childRole, toSet);
                }


                nodes.put(treeWrapper, WrapperFactory.wrap(treeCopy));
            } else { // second time we see this node; it's now the root
                currentRoot = treeCopy;

                if (actualRoot == null)
                    actualRoot = (CtClass<?>) currentRoot;
            }
        }

        private static CtElement copyTree(CtElement tree, CtElement root) {
            CtElement treeCopy = tree.clone();
            for (CtElement child : treeCopy.getDirectChildren()) {
                child.delete();
            }
            treeCopy.setAllMetadata(new HashMap<>()); // empty the metadata
            treeCopy.setParent(root);
            return treeCopy;
        }
    }

}
