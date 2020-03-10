package se.kth.spork.spoon;

import se.kth.spork.base3dm.*;
import se.kth.spork.util.Pair;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class for interpreting a merged PCS structure into a Spoon tree.
 *
 * @author Simon Larsén
 */
public class PcsInterpreter {
    private final Map<SpoonNode, Map<SpoonNode, Pcs<SpoonNode>>> rootToChildren;
    private final Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts;
    private final Builder visitor;

    /**
     * Convert a merged PCS structure into a Spoon tree.
     *
     * @param baseLeft  A tree matching between the base revision and the left revision.
     * @param baseRight A tree matching between the base revision and the right revision.
     * @return A Spoon tree representing the merged PCS structure.
     */
    public static CtElement fromMergedPcs(
            TStar<SpoonNode, RoledValues> delta,
            SpoonMapping baseLeft,
            SpoonMapping baseRight) {
        PcsInterpreter pcsInterpreter = new PcsInterpreter(delta, baseLeft, baseRight);
        pcsInterpreter.traversePcs(NodeFactory.ROOT);
        return pcsInterpreter.visitor.actualRoot;
    }

    private PcsInterpreter(TStar<SpoonNode, RoledValues> delta, SpoonMapping baseLeft, SpoonMapping baseRight) {
        rootToChildren = buildRootToChildren(delta.getStar());
        visitor = new Builder(delta.getContents(), baseLeft, baseRight);
        this.structuralConflicts = delta.getStructuralConflicts();
        // TODO what to do about root conflicts?
    }

    private static <T extends ListNode> Map<T, Map<T, Pcs<T>>> buildRootToChildren(Set<Pcs<T>> pcses) {
        Map<T, Map<T, Pcs<T>>> rootToChildren = new HashMap<>();
        for (Pcs<T> pcs : pcses) {
            Map<T, Pcs<T>> children = rootToChildren.getOrDefault(pcs.getRoot(), new HashMap<>());
            if (children.isEmpty()) rootToChildren.put(pcs.getRoot(), children);

            children.put(pcs.getPredecessor(), pcs);
        }

        return rootToChildren;
    }

    private static boolean isRootConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getRoot(), right.getRoot()) &&
                Objects.equals(left.getPredecessor(), right.getPredecessor()) ||
                Objects.equals(left.getSuccessor(), right.getSuccessor());
    }

    private static boolean isPredecessorConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getPredecessor(), right.getPredecessor()) &&
                Objects.equals(left.getSuccessor(), right.getSuccessor()) &&
                Objects.equals(left.getRoot(), right.getRoot());
    }

    private static boolean isSuccessorConflict(Pcs<?> left, Pcs<?> right) {
        return !Objects.equals(left.getSuccessor(), right.getSuccessor()) &&
                Objects.equals(left.getPredecessor(), right.getPredecessor()) &&
                Objects.equals(left.getRoot(), right.getRoot());
    }

    private void traversePcs(SpoonNode currentRoot) {
        Map<SpoonNode, Pcs<SpoonNode>> children = rootToChildren.get(currentRoot);
        if (children == null) // leaf node
            return;

        SpoonNode next = NodeFactory.startOfChildList(currentRoot);
        List<SpoonNode> sortedChildren = new ArrayList<>();
        while (true) {
            Pcs<SpoonNode> nextPcs = children.get(next);

            next = nextPcs.getSuccessor();
            if (next.isEndOfList()) {
                break;
            }

            Set<Pcs<SpoonNode>> conflicts = structuralConflicts.get(nextPcs);
            Optional<Pcs<SpoonNode>> successorConflict = conflicts == null ? Optional.empty() :
                    conflicts.stream().filter(confPcs -> isSuccessorConflict(nextPcs, confPcs)).findFirst();

            // successor conflicts mark the start of a conflict, any other conflict is to be ignored
            if (successorConflict.isPresent()) {
                next = traverseConflict(nextPcs, successorConflict.get(), currentRoot, children);
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
     * @param nextPcs     The PCS triple currently being processed.
     * @param conflicting A PCS triple conflicting with the one currently being processed. This is assumed
     *                    to be a successor conflict (i.e. on the form Pcs(a, b, c), Pcs(a, b, c')).
     * @param currentRoot The current root node.
     * @param children    The children of the current root node.
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

        Pcs<SpoonNode> leftPcs = nextPcs.getRevision() == Revision.LEFT ? nextPcs : conflicting;
        Pcs<SpoonNode> rightPcs = leftPcs == nextPcs ? conflicting : nextPcs;

        List<SpoonNode> leftNodes = extractConflictList(leftPcs, children);
        List<SpoonNode> rightNodes = extractConflictList(rightPcs, children);

        Optional<List<SpoonNode>> resolved = tryResolveConflict(leftNodes, rightNodes);
        if (resolved.isPresent()) {
            for (SpoonNode node : resolved.get()) {
                visitor.visit(currentRoot, node);
                traversePcs(node);
            }
        } else {
            visitor.visitConflicting(currentRoot, leftNodes, rightNodes);
            visitor.endConflict();
        }

        return leftNodes.isEmpty() ? next : leftNodes.get(leftNodes.size() - 1);
    }

    /**
     * Scan ahead in the PCS structure to resolve the conflicting children. The conflict must end with a
     * predecessor conflict, or an exception is thrown.
     */
    private List<SpoonNode> extractConflictList(Pcs<SpoonNode> pcs, Map<SpoonNode, Pcs<SpoonNode>> siblings) {
        List<SpoonNode> nodes = new ArrayList<>();

        while (true) {
            Set<Pcs<SpoonNode>> conflicts = structuralConflicts.get(pcs);

            if (conflicts != null && !conflicts.isEmpty()) {
                Pcs<SpoonNode> finalPcs = pcs;
                Optional<Pcs<SpoonNode>> predConflict = conflicts.stream()
                        .filter(confPcs -> isPredecessorConflict(finalPcs, confPcs)).findFirst();

                if (predConflict.isPresent()) {
                    return nodes;
                }
            }

            SpoonNode nextNode = pcs.getSuccessor();

            if (nextNode.isEndOfList())
                throw new IllegalStateException(
                        "Reached the end of the child list without finding a predecessor conflict");

            nodes.add(nextNode);
            pcs = siblings.get(nextNode);
        }
    }

    /**
     * Try to resolve a structural conflict automatically.
     */
    private static Optional<List<SpoonNode>> tryResolveConflict(List<SpoonNode> leftNodes, List<SpoonNode> rightNodes) {
        SpoonNode firstNode = leftNodes.size() > 0 ? leftNodes.get(0) : rightNodes.get(0);
        if (!(firstNode.getElement().getRoleInParent() == CtRole.TYPE_MEMBER))
            return Optional.empty();

        assert leftNodes.stream().allMatch(node -> node.getElement().getRoleInParent() == CtRole.TYPE_MEMBER);
        assert rightNodes.stream().allMatch(node -> node.getElement().getRoleInParent() == CtRole.TYPE_MEMBER);

        // FIXME this is too liberal. Fields are not unordered, and this approach makes the merge non-commutative.
        List<SpoonNode> result = Stream.of(leftNodes, rightNodes).flatMap(List::stream).collect(Collectors.toList());
        return Optional.of(result);
    }

    private static class Builder {
        private CtElement actualRoot;
        private Map<SpoonNode, Set<Content<SpoonNode, RoledValues>>> contents;
        private SpoonMapping baseLeft;
        private SpoonMapping baseRight;
        private boolean inConflict = false;


        // A mapping from a node in the input PCS structure to its copy in the merged tree
        private Map<SpoonNode, SpoonNode> nodes;

        private Builder(Map<SpoonNode, Set<Content<SpoonNode, RoledValues>>> contents, SpoonMapping baseLeft, SpoonMapping baseRight) {
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
            CtElement mergeParent = origRootWrapper == NodeFactory.ROOT ? null : nodes.get(origRootWrapper).getElement();

            CtElement originalTree = origTreeWrapper.getElement();
            CtElement originalRoot = origRootWrapper.getElement();

            CtElement mergeTree = shallowCopyTree(originalTree);
            if (!inConflict) {
                // content should only be merged if not in a conflict
                // when in a conflict, the original tree's content should always be used
                setContent(mergeTree, origTreeWrapper);
            }

            if (mergeParent != null) {
                CtRole mergeTreeRole = resolveRole(origTreeWrapper);
                Object inserted = withSiblings(originalRoot, originalTree, mergeParent, mergeTree, mergeTreeRole);
                mergeParent.setValueByRole(mergeTreeRole, inserted);
            }

            assert !nodes.containsKey(origTreeWrapper); // if this happens, then there is a duplicate node in the tree
            nodes.put(origTreeWrapper, NodeFactory.wrap(mergeTree));

            if (actualRoot == null)
                actualRoot = mergeParent;
        }

        private Object withSiblings(
                CtElement originalRoot,
                CtElement originalTree,
                CtElement mergeParent,
                CtElement mergeTree,
                CtRole mergeTreeRole) {
            Object siblings = mergeParent.getValueByRole(mergeTreeRole);
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
                toSet = resolveAnnotationMap(mergeTree, (Map<?, ?>) siblings, originalRoot, originalTree);
            } else {
                toSet = mergeTree;
            }

            return toSet;
        }

        /**
         * Visit the root nodes of a conflict. Note that the children of these nodes are not visited
         * by this method, it is the responsibility of the caller to visit the children, and then call
         * the {@link Builder#endConflict()} once the conflict has been concluded.
         *
         * @param parent The parent node of the conflict.
         * @param left   Ordered root nodes from the left part of the conflict.
         * @param right  Ordered root nodes from the right part of the conflict.
         */
        public void visitConflicting(SpoonNode parent, List<SpoonNode> left, List<SpoonNode> right) {
            inConflict = true;

            CtElement mergeParent = nodes.get(parent).getElement();
            CtElement dummy = (left.size() > 0 ? left.get(0) : right.get(0)).getElement();

            dummy.putMetadata(StructuralConflict.METADATA_KEY, new StructuralConflict(
                    left.stream().map(SpoonNode::getElement).collect(Collectors.toList()),
                    right.stream().map(SpoonNode::getElement).collect(Collectors.toList())));
            SpoonNode dummyNode = NodeFactory.wrap(dummy);
            CtRole role = resolveRole(dummyNode);

            Object inserted = withSiblings(parent.getElement(), dummy, mergeParent, dummy, role);
            dummy.delete();
            mergeParent.setValueByRole(role, inserted);
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
         * @param originalRoot      The root from which the mergeParent was copied.
         * @param originalTree      The tree from which mergeTree was copied.
         * @return A map representing the key/value pairs of an annotation, wich mergeTree inserted among its siblings.
         */
        private Map<?, ?> resolveAnnotationMap(
                CtElement mergeTree, Map<?, ?> siblings, CtElement originalRoot, CtElement originalTree) {

            Map<Object, Object> mutableCurrent = new TreeMap<>(siblings);

            // To find the key for the value, we find the key of the original value
            // in the original annotation. This intuitively seems like it should work,
            // but complex modifications to annotations may cause this to crash and burn.
            // TODO review if this approach is feasible
            CtAnnotation<?> annotation = (CtAnnotation<?>) originalRoot;
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
         * Set the content of a tree that is being/has been merged to the merged content of the original tree.
         *
         * @param mergeTree    A tree in the merge output.
         * @param originalTree A wrapper around the tree from which mergeTree was copied.
         */
        private void setContent(CtElement mergeTree, SpoonNode originalTree) {
            Set<Content<SpoonNode, RoledValues>> nodeContents = contents.get(originalTree);

            if (nodeContents.size() != 1) {
                throw new IllegalStateException("Internal error, unhandled conflict: " + nodeContents);
            } else {
                RoledValues rvs = nodeContents.iterator().next().getValue();

                for (RoledValue roledValue : rvs) {
                    mergeTree.setValueByRole(roledValue.getRole(), roledValue.getValue());
                }
            }
        }

    }

    /**
     * Create a shallow copy of a tree.
     *
     * @param tree A tree to copy.
     * @return A shallow copy of the input tree.
     */
    public static CtElement shallowCopyTree(CtElement tree) {
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

}
