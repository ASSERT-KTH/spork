package se.kth.spork.merge.spoon;

import se.kth.spork.merge.*;
import se.kth.spork.util.Pair;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

import java.util.*;

/**
 * Class for converting between a Spoon tree and a PCS structure.
 *
 * @author Simon Larsén
 */
public class SpoonPcs {
    private final Map<SpoonNode, Map<SpoonNode, Pcs<SpoonNode>>> rootToChildren;
    private final Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts;
    private final Builder visitor;

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
     * @param baseLeft            A tree matching between the base revision and the left revision.
     * @param baseRight           A tree matching between the base revision and the right revision.
     * @return A Spoon tree representing the merged PCS structure.
     */
    public static CtElement fromMergedPcs(
            TStar<SpoonNode, RoledValue> delta,
            SpoonMapping baseLeft,
            SpoonMapping baseRight) {
        SpoonPcs spoonPcs = new SpoonPcs(delta, baseLeft, baseRight);
        spoonPcs.traversePcs(null);
        return spoonPcs.visitor.actualRoot;
    }

    private SpoonPcs(TStar<SpoonNode, RoledValue> delta, SpoonMapping baseLeft, SpoonMapping baseRight) {
        rootToChildren = buildRootToChildren(delta.getStar());
        visitor = new Builder(delta.getContents(), baseLeft, baseRight);
        this.structuralConflicts = delta.getStructuralConflicts();
        // TODO what to do about root conflicts?
        removePredecessorConflicts(structuralConflicts);
    }

    private static <T> Map<T, Map<T, Pcs<T>>> buildRootToChildren(Set<Pcs<T>> pcses) {
        Map<T, Map<T, Pcs<T>>> rootToChildren = new HashMap<>();
        for (Pcs<T> pcs : pcses) {
            Map<T, Pcs<T>> children = rootToChildren.getOrDefault(pcs.getRoot(), new HashMap<>());
            if (children.isEmpty()) rootToChildren.put(pcs.getRoot(), children);

            children.put(pcs.getPredecessor(), pcs);
        }

        return rootToChildren;
    }

    /**
     * Remove any predecessor conflicts (i.e. conflicts on the form Pcs(a, b, c), Pcs(a, b', c)) from the
     * structural conflicts. Predecessor conflicts mark the _end_ of conflicting segments, but we only
     * want the starts.
     *
     * @param structuralConflicts A mapping of structural conflicts.
     */
    private static void removePredecessorConflicts(Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts) {
        for (Pcs<SpoonNode> pcs : new ArrayList<>(structuralConflicts.keySet())) {
            Iterator<Pcs<SpoonNode>> it = structuralConflicts.get(pcs).iterator();
            while (it.hasNext()) {
                Pcs<SpoonNode> other = it.next();

                if (isPredecessorConflict(pcs, other)) {
                    it.remove();
                    structuralConflicts.get(other).remove(pcs);
                } else if (isRootConflict(pcs, other)) {
                    throw new RuntimeException("Can't handle root conflict: " + pcs + ", " + other);
                }
            }
        }
    }

    private static boolean isRootConflict(Pcs<?> left, Pcs<?> right) {
        return left.getRoot() != right.getRoot();
    }

    private static boolean isPredecessorConflict(Pcs<?> left, Pcs<?> right) {
        return left.getPredecessor() != right.getPredecessor();
    }

    private void traversePcs(SpoonNode currentRoot) {
        Map<SpoonNode, Pcs<SpoonNode>> children = rootToChildren.get(currentRoot);
        if (children == null) // leaf node
            return;

        SpoonNode next = null;
        List<SpoonNode> sortedChildren = new ArrayList<>();
        while (true) {
            Pcs<SpoonNode> nextPcs = children.get(next);
            next = nextPcs.getSuccessor();
            if (next == null) {
                break;
            }

            Set<Pcs<SpoonNode>> conflicts = structuralConflicts.get(nextPcs);
            if (conflicts != null && conflicts.size() > 0) {
                assert conflicts.size() == 1; // this should have been seen to in pre-processing of conflicts
                Pcs<SpoonNode> conflictingPcs = conflicts.iterator().next();
                next = traverseConflict(nextPcs, conflictingPcs, currentRoot, children);
            } else {
                visitor.visit(currentRoot, next);
                sortedChildren.add(next);
            }
        }

        sortedChildren.forEach(this::traversePcs);
    }

    /**
     * Traverse all nodes in the conflict.
     *
     * @param nextPcs The PCS triple currently being processed.
     * @param conflicting A PCS triple conflicting with the one currently being processed. This is assumed
     *                    to be a successor conflict (i.e. on the form Pcs(a, b, c), Pcs(a, b, c')).
     * @param currentRoot The current root node.
     * @param children The children of the current root node.
     * @return The first node in the left tree that immediately follows the conflict. This is the
     * next node to process.
     */
    private SpoonNode traverseConflict(
            Pcs<SpoonNode> nextPcs,
            Pcs<SpoonNode> conflicting,
            SpoonNode currentRoot,
            Map<SpoonNode, Pcs<SpoonNode>> children) {
        SpoonNode next = nextPcs.getSuccessor();
        SpoonNode conflictingNode = conflicting.getSuccessor();

        SpoonNode left = conflicting.getRevision() == Revision.RIGHT ? next : conflictingNode;
        SpoonNode right = left == next ? conflictingNode : next;

        List<SpoonNode> leftNodes = extractConflictList(left, children);
        List<SpoonNode> rightNodes = extractConflictList(right, children);
        Pair<Integer, Integer> cutoffs = findConflictListCutoffs(leftNodes, rightNodes);
        leftNodes = leftNodes.subList(0, cutoffs.first);
        rightNodes = rightNodes.subList(0, cutoffs.second);

        visitor.visitConflicting(currentRoot, leftNodes, rightNodes);
        for (SpoonNode node : leftNodes) {
            traversePcs(node);
        }
        for (SpoonNode node : rightNodes) {
            traversePcs(node);
        }
        visitor.endConflict();

        return leftNodes.isEmpty() ? next : leftNodes.get(leftNodes.size() - 1);
    }

    /**
     * Scan ahead in the PCS structure to resolve the conflicting children. The start node is assumed to
     * be the first conflicting node, and the end of the conflict is taken as the first node that is
     * mapped to the base revision.
     */
    private static <V extends SpoonNode> List<V> extractConflictList(V start, Map<V, Pcs<V>> siblings) {
        List<V> nodes = new ArrayList<V>();
        V cur = start;
        while (cur != null && cur.getElement().getMetadata(TdmMerge.REV) != Revision.BASE) {
            nodes.add(cur);
            cur = siblings.get(cur).getSuccessor();
        }
        return nodes;
    }

    /**
     * Find the cutoff points in the left and right conflict lists, where they have elements in common.
     */
    private static <V extends SpoonNode> Pair<Integer, Integer> findConflictListCutoffs(List<V> left, List<V> right) {
        // this algorithm is O(n^2), but conflict lists are typically short so it shouldn't matter in practice
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                if (left.get(i) == right.get(j))
                    return new Pair<>(i, j);
            }
        }
        return new Pair<>(left.size(), right.size());
    }

    private static class Builder {
        private CtElement actualRoot;
        private Map<SpoonNode, Set<Content<SpoonNode, RoledValue>>> contents;
        private SpoonMapping baseLeft;
        private SpoonMapping baseRight;
        private boolean inConflict = false;


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
        public void visit(SpoonNode origRootWrapper, SpoonNode origTreeWrapper) {
            CtElement mergeParent = origRootWrapper == null ? null : nodes.get(origRootWrapper).getElement();

            CtElement originalTree = origTreeWrapper.getElement();

            CtElement mergeTree = shallowCopyTree(originalTree);
            if (!inConflict) {
                // content should only be merged if not in a conflict
                // when in a conflict, the original tree's content should always be used
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

            assert !nodes.containsKey(origTreeWrapper); // if this happens, then there is a duplicate node in the tree
            nodes.put(origTreeWrapper, NodeFactory.wrap(mergeTree));

            if (actualRoot == null)
                actualRoot = mergeParent;
        }

        /**
         * Visit the root nodes of a conflict. Note that the children of these nodes are not visited
         * by this method, it is the responsibility of the caller to visit the children, and then call
         * the {@link Builder#endConflict()} once the conflict has been concluded.
         *
         * @param parent The parent node of the conflict.
         * @param left Ordered root nodes from the left part of the conflict.
         * @param right Ordered root nodes from the right part of the conflict.
         */
        public void visitConflicting(SpoonNode parent, List<SpoonNode> left, List<SpoonNode> right) {
            inConflict = true;
            if (left.size() > 0) {
                // if the left part is empty, the start marker will be missing in the conflict
                // this is handled in the pretty printer
                SpoonNode firstLeft = left.get(0);
                firstLeft.getElement().putMetadata(ConflictInfo.CONFLICT_METADATA,
                        new ConflictInfo(left.size(), right.size(), ConflictInfo.ConflictMarker.LEFT_START));
            }
            if (right.size() > 0) {
                // if the right part is empty, this marker will be missing in the conflict
                SpoonNode firstRight = right.get(0);
                firstRight.getElement().putMetadata(ConflictInfo.CONFLICT_METADATA,
                        new ConflictInfo(left.size(), right.size(), ConflictInfo.ConflictMarker.RIGHT_START));
            }
            if (right.size() > 1) {
                SpoonNode lastRight = right.get(right.size() - 1);
                lastRight.getElement().putMetadata(ConflictInfo.CONFLICT_METADATA,
                        new ConflictInfo(left.size(), right.size(), ConflictInfo.ConflictMarker.RIGHT_END));
            }

            for (SpoonNode leftNode : left) {
                visit(parent, leftNode);
            }

            for (SpoonNode rightNode : right) {
                visit(parent, rightNode);
            }
        }

        /**
         * Signal the end of a structural conflict. Should be called when all children of the root conflict nodes
         * have been visited.
         */
        public void endConflict() {
            inConflict = false;
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

            // remove the wrapper metadata
            Map<String, Object> metadata = new HashMap<>(treeCopy.getAllMetadata());
            metadata.remove(NodeFactory.WRAPPER_METADATA);
            treeCopy.setAllMetadata(metadata);

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

            RoledValue roledValue = nodeContents.size() == 1 ?
                    nodeContents.iterator().next().getValue() : handleContentConflict(nodeContents);
            if (roledValue.getRole() != null) {
                mergeTree.setValueByRole(roledValue.getRole(), roledValue.getValue());
            }
        }

        /**
         * Prototype handling of a content conflict. Essentially, it just resolves the left and right values,
         * and concatenates them with conflict delimiters. Very crude, but seems to work.
         * <p>
         * TODO This is a severely limited approach as adjacent conflicts cannot be joined together, find a better way!
         * <p>
         * One solution would be to do text-processing afterwards and just find adjacent conflicts, they will be
         * very easy to find due to a ">>>>>>> RIGHT" line immediately preceeding a "<<<<<<< LEFT" line (possibly
         * with a blank line in between).
         *
         * @param nodeContents A set of contents for the node, containing at least left and right revisions.
         * @return A "conflict" value for the node.
         */
        private RoledValue handleContentConflict(Set<Content<SpoonNode, RoledValue>> nodeContents) {
            String leftContent = null;
            String rightContent = null;
            CtRole role = null;

            for (Content<SpoonNode, RoledValue> content : nodeContents) {
                if (role == null)
                    role = content.getValue().getRole();

                switch (content.getContext().getRevision()) {
                    case LEFT:
                        leftContent = content.getValue().getValue().toString();
                        break;
                    case RIGHT:
                        rightContent = content.getValue().getValue().toString();
                        break;
                    default:
                        // skip base revision for now
                }
            }
            assert leftContent != null;
            assert rightContent != null;
            assert role != null;

            String conflict = "\n<<<<<<< LEFT\n" + leftContent + "\n=======\n" + rightContent + "\n>>>>>>> RIGHT\n";
            return new RoledValue(conflict, role);
        }
    }
}
