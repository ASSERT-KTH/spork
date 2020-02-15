package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Content;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Class for going from a Spoon tree to a PCS structure.
 *
 * @author Simon Larsén
 */
public class SpoonPcs {

    public static Set<Pcs<CtWrapper>> fromSpoon(CtElement spoonClass, Revision revision) {
        TreeScanner scanner = new TreeScanner(revision);
        scanner.scan(spoonClass);
        return scanner.getPcses();
    }

    public static CtClass<?> fromPcs(Set<Pcs<CtWrapper>> pcses, Map<CtWrapper, Set<Content<CtWrapper>>> contents, SpoonMapping baseLeft, SpoonMapping baseRight) {
        Builder builder = new Builder(contents, baseLeft, baseRight);
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
        private Map<CtWrapper, Set<Content<CtWrapper>>> contents;
        private SpoonMapping baseLeft;
        private SpoonMapping baseRight;

        private Builder(Map<CtWrapper, Set<Content<CtWrapper>>> contents, SpoonMapping baseLeft, SpoonMapping baseRight) {
            nodes = new HashMap<>();
            this.contents = contents;
            this.baseLeft = baseLeft;
            this.baseRight = baseRight;
        }

        /**
         * Resolving the role of a node in the merged tree is tricky, but with a few assumptions it can be done
         * quickly.
         *
         * First of all, it is fairly safe to assume that the node can have at most two roles. Assume for a second
         * that a node could have three roles. This means that the node has been modified inconsistently in the left
         * and right revisions, and by the definition of 3DM merge there will have been a structural conflict already.
         *
         * Second, it is also safe to assume that if the role differs between base and either left or right, the role
         * in base should be discarded. This is safe to assume as all edits of left and right will appear in the
         * merged tree.
         *
         * Thus, given that the base revision's role is resolved, it will always be possible to resolve the unique
         * role that should be applied next. This also means that a problem occurs when a left-to-right mapping is
         * used, as there may then be nodes that only match between left and right, and no clear way of determining
         * which of the two roles should be used, if they differ. I have yet to figure out how to resolve that.
         *
         * @param wrapper
         * @return
         */
        private CtRole resolveRole(CtWrapper wrapper) {
            List<CtRole> matches = new ArrayList<>();
            CtElement tree = wrapper.getElement();
            matches.add(wrapper.getElement().getRoleInParent());

            Optional<CtWrapper> base = Optional.empty();

            switch ((Revision) tree.getMetadata(TdmMerge.REV)) {
                case BASE: {
                    base = Optional.of(wrapper);
                    CtWrapper left = baseLeft.getDst(wrapper);
                    CtWrapper right = baseRight.getDst(wrapper);
                    if (left != null)
                        matches.add(left.getElement().getRoleInParent());
                    if (right != null)
                        matches.add(right.getElement().getRoleInParent());
                } break;
                case RIGHT: {
                    CtWrapper match = baseRight.getSrc(wrapper);
                    if (match != null) {
                        matches.add(match.getElement().getRoleInParent());
                        base = Optional.of(match);
                    }
                } break;
                case LEFT: {
                    CtWrapper match = baseLeft.getSrc(wrapper);
                    if (match != null) {
                        matches.add(match.getElement().getRoleInParent());
                        base = Optional.of(match);
                    }
                } break;
                default:
                    throw new IllegalStateException("unmatched revision");
            }

            if (base.isPresent()) {
                CtRole baseRole = base.get().getElement().getRoleInParent();
                matches.removeIf(w -> w == baseRole);

                if (matches.isEmpty()) {
                    return baseRole;
                }
            }

            assert matches.size() == 1;
            return matches.get(0);
        }

        @Override
        public void accept(CtWrapper treeWrapper) {
            CtElement tree = treeWrapper.getElement();
            CtWrapper treeCopyWrapper = nodes.get(treeWrapper);
            CtElement treeCopy = treeCopyWrapper == null ? null : treeCopyWrapper.getElement();

            if (treeCopy == null) { // first time we see this node; it's a child node of the current root

                treeCopy = copyTree(tree, currentRoot);



                if (currentRoot != null) {
                    CtRole childRole = resolveRole(treeWrapper);

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

        private CtElement copyTree(CtElement tree, CtElement root) {
            CtElement treeCopy = tree.clone();
            for (CtElement child : treeCopy.getDirectChildren()) {
                child.delete();
            }
            treeCopy.setAllMetadata(new HashMap<>()); // empty the metadata

            if (treeCopy instanceof CtLiteral) {
                CtWrapper wrapped = WrapperFactory.wrap(tree);
                Set<Content<CtWrapper>> value = contents.get(wrapped);
                if (value != null) {
                    ((CtLiteral) treeCopy).setValue(value.iterator().next().getValue());
                }
            }

            treeCopy.setParent(root);
            return treeCopy;
        }
    }

}
