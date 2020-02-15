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
    public static Set<Pcs<CtElement>> fromSpoon(CtClass<?> spoonClass) {
        Set<Pcs<CtElement>> result = new HashSet<>();

        result.add(newPcs(null, spoonClass, null));
        result.add(newPcs(null, null, spoonClass));

        Deque<CtElement> stack = new ArrayDeque<>();
        stack.push(spoonClass);

        while (!stack.isEmpty()) {
            CtElement root = stack.pop();
            CtElement predecessor = null;

            for (CtElement successor : root.getDirectChildren()) {
                if (successor.isImplicit())
                    continue;

                stack.add(successor);
                result.add(newPcs(root, predecessor, successor));
                predecessor = successor;
            }

            result.add(newPcs(root, predecessor, null));
        }

        return result;
    }

    public static Set<Pcs<CtElement>> fromSpoonWithScanner(CtClass<?> spoonClass) {
        TreeScanner scanner = new TreeScanner();
        scanner.scan(spoonClass);
        return scanner.getPcses();
    }

    public static CtClass<?> fromPcs(Set<Pcs<CtElement>> pcses) {
        IdentifierSupport idSup = IdentifierSupport.getInstance();
        Builder builder = new Builder();
        traversePcs(pcses, builder, idSup::getKey);
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
    private static <K,V> void traversePcs(Set<Pcs<V>> pcses, Consumer<V> visit, Function<V, K> getKey) {
        Map<K, Map<K, Pcs<V>>> rootToChildren = new HashMap<>();
        for (Pcs<V> pcs : pcses) {
            K rootKey = getKey.apply(pcs.getRoot());
            Map<K, Pcs<V>> children = rootToChildren.getOrDefault(rootKey, new HashMap<>());
            if (children.isEmpty()) rootToChildren.put(rootKey, children);

            K predKey = getKey.apply(pcs.getPredecessor());
            children.put(predKey, pcs);
        }

        traversePcs(rootToChildren, null, visit, getKey);
    }

    private static <K,V> void traversePcs(Map<K, Map<K, Pcs<V>>> rootToChildren, V currentRoot, Consumer<V> visit, Function<V, K> getKey) {
        if (currentRoot != null)
            visit.accept(currentRoot);
        Map<K, Pcs<V>> children = rootToChildren.get(getKey.apply(currentRoot));
        if (children == null) // leaf node
            return;

        V pred = null;
        List<V> sortedChildren = new ArrayList<>();
        while (true) {
            K predKey = getKey.apply(pred);
            Pcs<V> nextPcs = children.get(predKey);
            if (nextPcs == null)
                System.out.println("oh no");
            pred = nextPcs.getSuccessor();
            if (pred == null) {
                break;
            }
            sortedChildren.add(pred);
            visit.accept(pred);
        };
        sortedChildren.forEach(child -> traversePcs(rootToChildren, child, visit, getKey));
    }

    private static class Builder implements Consumer<CtElement> {
        private CtElement currentRoot;
        private CtClass<?> actualRoot;
        private Map<Long, CtElement> nodes;
        private IdentifierSupport idSup;

        private Builder() {
            nodes = new HashMap<>();
            idSup = IdentifierSupport.getInstance();
        }

        @Override
        public void accept(CtElement tree) {
            CtElement treeCopy = nodes.get(idSup.getKey(tree));

            if (treeCopy == null) { // first time we see this node; it's a child node of the current root

                treeCopy = copyTree(tree,  currentRoot);

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


                nodes.put(idSup.getKey(tree), treeCopy);
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
            treeCopy.setParent(root);
            return treeCopy;
        }
    }

}
