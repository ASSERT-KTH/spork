package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Content;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Class for converting between a Spoon tree and a PCS structure.
 *
 * @author Simon Larsén
 */
public class SpoonPcs {

    /**
     * Convert a Spoon tree into a PCS structure.
     *
     * @param spoonClass A Spoon class.
     * @param revision The revision this Spoon class belongs to. The revision is attached to each PCS triple.
     * @return The Spoon tree represented by PCS triples.
     */
    public static Set<Pcs<SpoonNode>> fromSpoon(CtElement spoonClass, Revision revision) {
        PcsBuilder scanner = new PcsBuilder(revision);
        scanner.scan(spoonClass);
        return scanner.getPcses();
    }

    /**
     * Convert a merged PCS structure into a Spoon tree.
     *
     * @param pcses A set of PCS triples.
     * @param contents A mapping from SpoonNode objects to their respective contents.
     * @param baseLeft A tree matching between the base revision and the left revision.
     * @param baseRight A tree matching between the base revision and the right revision.
     * @return A Spoon tree representing the merged PCS structure.
     */
    public static CtElement fromMergedPcs(
            Set<Pcs<SpoonNode>> pcses,
            Map<SpoonNode, Set<Content<SpoonNode, RoledValue>>> contents,
            SpoonMapping baseLeft,
            SpoonMapping baseRight) {
        Builder builder = new Builder(contents, baseLeft, baseRight);
        traversePcs(pcses, builder);
        return builder.actualRoot;
    }

    /**
     * Traverses the PCS structure and visits each (parent, node) pair. That is to say, when a node is visited, its
     * parent is also made available. This is necessary to be able to rebuild a tree.
     *
     * @param pcses A well-formed PCS structure.
     * @param visit A function to apply to the nodes in the PCS structure.
     */
    private static <V> void traversePcs(Set<Pcs<V>> pcses, BiConsumer<V, V> visit) {
        Map<V, Map<V, Pcs<V>>> rootToChildren = new HashMap<>();
        for (Pcs<V> pcs : pcses) {
            Map<V, Pcs<V>> children = rootToChildren.getOrDefault(pcs.getRoot(), new HashMap<>());
            if (children.isEmpty()) rootToChildren.put(pcs.getRoot(), children);

            children.put(pcs.getPredecessor(), pcs);
        }

        traversePcs(rootToChildren, null, visit);
    }

    private static <V> void traversePcs(Map<V, Map<V, Pcs<V>>> rootToChildren, V currentRoot, BiConsumer<V, V> visit) {
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
            visit.accept(currentRoot, pred);
        };
        sortedChildren.forEach(child -> traversePcs(rootToChildren, child, visit));
    }

    private static class Builder implements BiConsumer<SpoonNode, SpoonNode> {
        private CtElement actualRoot;
        private Map<SpoonNode, SpoonNode> nodes;
        private Map<SpoonNode, Set<Content<SpoonNode, RoledValue>>> contents;
        private SpoonMapping baseLeft;
        private SpoonMapping baseRight;

        private Builder(Map<SpoonNode, Set<Content<SpoonNode, RoledValue>>> contents, SpoonMapping baseLeft, SpoonMapping baseRight) {
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
         * @param wrapper A wrapped Spoon node.
         * @return The resolved role of this node in the merged tree.
         */
        private CtRole resolveRole(SpoonNode wrapper) {
            List<CtRole> matches = new ArrayList<>();
            CtElement tree = wrapper.getElement();
            matches.add(wrapper.getElement().getRoleInParent());

            Optional<SpoonNode> base = Optional.empty();

            switch ((Revision) tree.getMetadata(TdmMerge.REV)) {
                case BASE: {
                    base = Optional.of(wrapper);
                    SpoonNode left = baseLeft.getDst(wrapper);
                    SpoonNode right = baseRight.getDst(wrapper);
                    if (left != null)
                        matches.add(left.getElement().getRoleInParent());
                    if (right != null)
                        matches.add(right.getElement().getRoleInParent());
                } break;
                case RIGHT: {
                    SpoonNode match = baseRight.getSrc(wrapper);
                    if (match != null) {
                        matches.add(match.getElement().getRoleInParent());
                        base = Optional.of(match);
                    }
                } break;
                case LEFT: {
                    SpoonNode match = baseLeft.getSrc(wrapper);
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

        /**
         * @param treeWrapper A wrapper around the current node being visited.
         * @param rootWrapper A wrapper around the current node's parent.
         */
        @Override
        public void accept(SpoonNode rootWrapper, SpoonNode treeWrapper) {
            CtElement currentRoot = rootWrapper == null ? null : nodes.get(rootWrapper).getElement();

            CtElement tree = treeWrapper.getElement();
            SpoonNode treeCopyWrapper = nodes.get(treeWrapper);
            CtElement treeCopy = treeCopyWrapper == null ? null : treeCopyWrapper.getElement();

            if (treeCopy == null) { // first time we see this node
                treeCopy = copyTree(tree, currentRoot);
            }

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


            nodes.put(treeWrapper, NodeFactory.wrap(treeCopy));

        if (actualRoot == null)
            actualRoot = currentRoot;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private CtElement copyTree(CtElement tree, CtElement root) {
            CtElement treeCopy = tree.clone();
            for (CtElement child : treeCopy.getDirectChildren()) {
                child.delete();
            }
            treeCopy.setAllMetadata(new HashMap<>()); // empty the metadata

            SpoonNode wrapped = NodeFactory.wrap(tree);
            Set<Content<SpoonNode, RoledValue>> nodeContents = contents.get(wrapped);
            setContent(treeCopy, nodeContents);

            treeCopy.setParent(root);
            return treeCopy;
        }

        private void setContent(CtElement node, Set<Content<SpoonNode, RoledValue>> nodeContents) {
            if (nodeContents.size() > 1) {
                throw new IllegalStateException("unexpected amount of content: " + nodeContents);
            }

            RoledValue roledValue = nodeContents.iterator().next().getValue();
            if (roledValue.getRole() != null) {
                node.setValueByRole(roledValue.getRole(), roledValue.getValue());
            }
        }
    }

}
