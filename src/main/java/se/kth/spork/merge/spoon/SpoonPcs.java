package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Content;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
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
     * @param revision   The revision this Spoon class belongs to. The revision is attached to each PCS triple.
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
     * @param pcses     A set of PCS triples.
     * @param contents  A mapping from SpoonNode objects to their respective contents.
     * @param baseLeft  A tree matching between the base revision and the left revision.
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
        }
        ;
        sortedChildren.forEach(child -> traversePcs(rootToChildren, child, visit));
    }

    private static class Builder implements BiConsumer<SpoonNode, SpoonNode> {
        private CtElement actualRoot;
        private Map<SpoonNode, Set<Content<SpoonNode, RoledValue>>> contents;
        private SpoonMapping baseLeft;
        private SpoonMapping baseRight;


        // A mapping from a node in the input PCS structure to its copy in the merged tree
        private Map<SpoonNode, SpoonNode> nodes;

        private Builder(Map<SpoonNode, Set<Content<SpoonNode, RoledValue>>> contents, SpoonMapping baseLeft, SpoonMapping baseRight) {
            nodes = new HashMap<>();
            this.contents = contents;
            this.baseLeft = baseLeft;
            this.baseRight = baseRight;
        }

        /**
         * Visit a node an merge it. Note that both the node being visited, and its parent, are the original nodes from
         * the input trees.
         *
         * @param origTreeWrapper A wrapper around the current node being visited.
         * @param origRootWrapper A wrapper around the current node's parent.
         */
        @Override
        public void accept(SpoonNode origRootWrapper, SpoonNode origTreeWrapper) {
            CtElement mergeParent = origRootWrapper == null ? null : nodes.get(origRootWrapper).getElement();

            CtElement originalTree = origTreeWrapper.getElement();
            SpoonNode mergeTreeWrapper = nodes.get(origTreeWrapper);
            CtElement mergeTree = mergeTreeWrapper == null ? null : mergeTreeWrapper.getElement();

            if (mergeTree == null) { // first time we see this node
                mergeTree = shallowCopyTree(originalTree);
                setContent(mergeTree, origTreeWrapper);
            }

            if (mergeParent != null) {
                CtRole childRole = resolveRole(origTreeWrapper);

                Object siblings = mergeParent.getValueByRole(childRole);
                Object toSet;

                if (siblings instanceof Collection) {
                    Collection<CtElement> mutableCurrent;
                    if (siblings instanceof Set) {
                        mutableCurrent = new HashSet<>((Collection) siblings);
                    } else if (siblings instanceof List) {
                        mutableCurrent = new ArrayList<>((Collection) siblings);
                    } else {
                        throw new IllegalStateException("unexpected value by role: " + siblings.getClass());
                    }
                    mutableCurrent.add(mergeTree);
                    toSet = mutableCurrent;
                } else if (siblings instanceof Map) {
                    toSet = resolveAnnotationMap(mergeTree, (Map<?, ?>) siblings, origRootWrapper, originalTree);
                } else {
                    toSet = mergeTree;
                }

                mergeParent.setValueByRole(childRole, toSet);
            }


            nodes.put(origTreeWrapper, NodeFactory.wrap(mergeTree));

            if (actualRoot == null)
                actualRoot = mergeParent;
        }

        /**
         * Resolving the role of a node in the merged tree is tricky, but with a few assumptions it can be done
         * quickly.
         * <p>
         * First of all, it is fairly safe to assume that the node can have at most two roles. Assume for a second
         * that a node could have three roles. This means that the node has been modified inconsistently in the left
         * and right revisions, and by the definition of 3DM merge there will have been a structural conflict already.
         * <p>
         * Second, it is also safe to assume that if the role differs between base and either left or right, the role
         * in base should be discarded. This is safe to assume as all edits of left and right will appear in the
         * merged tree.
         * <p>
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
                }
                break;
                case RIGHT: {
                    SpoonNode match = baseRight.getSrc(wrapper);
                    if (match != null) {
                        matches.add(match.getElement().getRoleInParent());
                        base = Optional.of(match);
                    }
                }
                break;
                case LEFT: {
                    SpoonNode match = baseLeft.getSrc(wrapper);
                    if (match != null) {
                        matches.add(match.getElement().getRoleInParent());
                        base = Optional.of(match);
                    }
                }
                break;
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
         * Resolve they key/value mapping that forms the "body" of an annotation, assuming that mergeTree is a new value
         * to be inserted (i.e. mergeTree's parent is an annotation).
         * <p>
         * This is a bit fiddly, as there are many ways in which the key/value map can be expressed in source code.
         * See <a href="https://docs.oracle.com/javase/tutorial/java/annotations/basics.html">the Oracle docs</a> for
         * more info on annotations.
         * <p>
         * Note: This method mutates none of the input.
         *
         * @param mergeTree         The tree node currently being merged, to be inserted as a value among siblings.
         * @param siblings          A potentially empty map of annotation keys->values currently in the merge tree's parent's
         *                          children, i.e. the siblings of the current mergeTree.
         * @param origParentWrapper Wrapped tree from which the merge tree's parent was originally copied.
         * @param originalTree      The tree from which mergeTree was copied.
         * @return A map representing the key/value pairs of an annotation, wich mergeTree inserted among its siblings.
         */
        private Map<?, ?> resolveAnnotationMap(
                CtElement mergeTree, Map<?, ?> siblings, SpoonNode origParentWrapper, CtElement originalTree) {

            Map<Object, Object> mutableCurrent = new TreeMap<>(siblings);

            // To find the key for the value, we find the key of the original value
            // in the original annotation. This intuitively seems like it should work,
            // but complex modifications to annotations may cause this to crash and burn.
            // TODO review if this approach is feasible
            CtAnnotation<?> annotation = (CtAnnotation<?>) origParentWrapper.getElement();
            Optional<Map.Entry<String, CtExpression>> originalEntry = annotation
                    .getValues().entrySet().stream().filter(
                            entry -> entry.getValue().equals(originalTree)).findFirst();

            if (!originalEntry.isPresent()) {
                throw new IllegalStateException(
                        "Internal error: unable to find key for annotation value " + mergeTree);
            }

            mutableCurrent.put(originalEntry.get().getKey(), mergeTree);

            return mutableCurrent;
        }

        /**
         * Create a shallow copy of a tree.
         *
         * @param tree A tree to copy.
         * @return A shallow copy of the input tree.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private CtElement shallowCopyTree(CtElement tree) {
            // FIXME This is super inefficient, cloning the whole tree just to delete all its children
            CtElement treeCopy = tree.clone();
            for (CtElement child : treeCopy.getDirectChildren()) {
                child.delete();
            }
            treeCopy.setAllMetadata(new HashMap<>()); // empty the metadata

            return treeCopy;
        }

        /**
         * Set the content of a tree that is being/has been merged to the merged content of the original tree.
         *
         * @param mergeTree    A tree in the merge output.
         * @param originalTree A wrapper around the tree from which mergeTree was copied.
         */
        private void setContent(CtElement mergeTree, SpoonNode originalTree) {
            Set<Content<SpoonNode, RoledValue>> nodeContents = contents.get(originalTree);

            if (nodeContents.size() > 1) {
                throw new IllegalStateException("unexpected amount of content: " + nodeContents);
            }

            RoledValue roledValue = nodeContents.iterator().next().getValue();
            if (roledValue.getRole() != null) {
                mergeTree.setValueByRole(roledValue.getRole(), roledValue.getValue());
            }
        }
    }

}
