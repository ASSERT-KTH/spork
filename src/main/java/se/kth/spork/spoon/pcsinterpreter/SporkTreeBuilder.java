package se.kth.spork.spoon.pcsinterpreter;

import java.util.*;
import java.util.stream.Collectors;
import se.kth.spork.base3dm.*;
import se.kth.spork.exception.ConflictException;
import se.kth.spork.spoon.conflict.ConflictType;
import se.kth.spork.spoon.conflict.StructuralConflict;
import se.kth.spork.spoon.conflict.StructuralConflictHandler;
import se.kth.spork.spoon.matching.SpoonMapping;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.LazyLogger;
import se.kth.spork.util.LineBasedMergeKt;
import se.kth.spork.util.Pair;

/**
 * Class for building a {@link SporkTree} from a merged {@link ChangeSet}.
 *
 * @author Simon Larsén
 */
class SporkTreeBuilder {
    private static final LazyLogger LOGGER = new LazyLogger(SporkTreeBuilder.class);

    private final Map<SpoonNode, Map<SpoonNode, Pcs<SpoonNode>>> rootToChildren;
    private final Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts;
    private final Map<SpoonNode, Set<Content<SpoonNode, RoledValues>>> contents;
    private int numStructuralConflicts;

    // keeps track of which nodes have been added to the tree all ready
    // if any node is added twice, there's an unresolved move conflict
    private final Set<SpoonNode> usedNodes;

    // keeps track of all structural inconsistencies that are used
    // if any have not been used when the tree has been built, there's something wrong
    private final Set<Pcs<SpoonNode>> remainingInconsistencies;

    private final SpoonMapping baseLeft;
    private final SpoonMapping baseRight;

    private final List<StructuralConflictHandler> conflictHandlers;

    /**
     * Create a builder.
     *
     * @param delta A merged change set.
     * @param baseLeft Base-to-left matchings.
     * @param baseRight Base-to-right matchings.
     * @param conflictHandlers Conflict handlers for structural conflicts.
     */
    public SporkTreeBuilder(
            ChangeSet<SpoonNode, RoledValues> delta,
            SpoonMapping baseLeft,
            SpoonMapping baseRight,
            List<StructuralConflictHandler> conflictHandlers) {
        this.rootToChildren = buildRootToChildren(delta.getPcsSet());
        this.baseLeft = baseLeft;
        this.baseRight = baseRight;
        this.conflictHandlers = new ArrayList<>(conflictHandlers);
        structuralConflicts = delta.getStructuralConflicts();
        contents = delta.getContents();
        numStructuralConflicts = 0;
        usedNodes = new HashSet<>();
        remainingInconsistencies = new HashSet<>();
        structuralConflicts.values().forEach(remainingInconsistencies::addAll);
    }

    private static <T extends ListNode> Map<T, Map<T, Pcs<T>>> buildRootToChildren(
            Set<Pcs<T>> pcses) {
        Map<T, Map<T, Pcs<T>>> rootToChildren = new HashMap<>();
        for (Pcs<T> pcs : pcses) {
            Map<T, Pcs<T>> children = rootToChildren.getOrDefault(pcs.getRoot(), new HashMap<>());
            if (children.isEmpty()) rootToChildren.put(pcs.getRoot(), children);

            children.put(pcs.getPredecessor(), pcs);
        }

        return rootToChildren;
    }

    /** Try to resolve a structural conflict automatically. */
    private Optional<List<SpoonNode>> tryResolveConflict(
            List<SpoonNode> leftNodes, List<SpoonNode> rightNodes) {
        // we can currently only resolve conflict lists for insert/insert conflicts
        // TODO Expand conflict handling to deal with more than just insert/insert
        final ConflictType conflictType = ConflictType.INSERT_INSERT;
        final List<SpoonNode> unmodifiableLeft = Collections.unmodifiableList(leftNodes);
        final List<SpoonNode> unmodifiableRight = Collections.unmodifiableList(rightNodes);
        return conflictHandlers.stream()
                .map(
                        handler ->
                                handler.tryResolveConflict(
                                        unmodifiableLeft, unmodifiableRight, conflictType))
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** @return The amount of structural conflicts. */
    public int numStructuralConflicts() {
        return numStructuralConflicts;
    }

    public SporkTree buildTree() {
        return build(NodeFactory.INSTANCE.getVirtualRoot());
    }

    /**
     * Build a subtree of the {@link ChangeSet} contained in this builder. The {@link SporkTree}
     * that's returned contains all information required to build the final Spoon tree, including
     * structural conflict information.
     *
     * @param currentRoot The node from which to build the subtree.
     * @return A {@link SporkTree} rooted in the provided root node.
     */
    public SporkTree build(SpoonNode currentRoot) {
        Map<SpoonNode, Pcs<SpoonNode>> children = rootToChildren.get(currentRoot);

        Set<Content<SpoonNode, RoledValues>> currentContent =
                contents.getOrDefault(currentRoot, Collections.emptySet());
        SporkTree tree = new SporkTree(currentRoot, currentContent);

        if (children == null) // leaf node
        return tree;

        try {
            build(currentRoot.getStartOfChildList(), tree, children);

            for (Pcs<SpoonNode> inconsistent : remainingInconsistencies) {
                if (inconsistent.getRoot().equals(tree.getNode())) {
                    throw new ConflictException("Missed conflict: " + inconsistent);
                }
            }
        } catch (NullPointerException | ConflictException e) {
            // could not resolve the child list
            // TODO improve design, should not have to catch exceptions like this
            LOGGER.warn(
                    () ->
                            "Failed to resolve child list of "
                                    + currentRoot.getElement().getShortRepresentation()
                                    + ". Falling back to line-based merge of this element.");
            StructuralConflict conflict = approximateConflict(currentRoot);
            tree = new SporkTree(currentRoot, currentContent, conflict);
            tree.setRevisions(Arrays.asList(Revision.BASE, Revision.LEFT, Revision.RIGHT));
        }

        return tree;
    }

    private void build(SpoonNode start, SporkTree tree, Map<SpoonNode, Pcs<SpoonNode>> children) {
        if (children == null) // leaf node
        return;

        SpoonNode next = start;
        while (true) {
            Pcs<SpoonNode> nextPcs = children.get(next);
            tree.addRevision(nextPcs.getRevision());

            next = nextPcs.getSuccessor();

            if (next.isListEdge()) {
                // can still have a conflict at the end of the child list
                getSuccessorConflict(nextPcs)
                        .map(conflicting -> traverseConflict(nextPcs, conflicting, children, tree));
                break;
            }

            if (next.isVirtual() && !next.isListEdge()) {
                build(next.getStartOfChildList(), tree, rootToChildren.get(next));
            } else {
                Optional<Pcs<SpoonNode>> successorConflict = getSuccessorConflict(nextPcs);
                if (successorConflict.isPresent()) {
                    Pcs<SpoonNode> conflicting = successorConflict.get();
                    next = traverseConflict(nextPcs, conflicting, children, tree);
                } else {
                    addChild(tree, build(next));
                }

                if (next.isEndOfList()) {
                    break;
                }
            }
        }
    }

    private Optional<Pcs<SpoonNode>> getSuccessorConflict(Pcs<SpoonNode> pcs) {
        Set<Pcs<SpoonNode>> conflicts = structuralConflicts.get(pcs);
        return conflicts == null
                ? Optional.empty()
                : conflicts.stream()
                        .filter(
                                confPcs ->
                                        StructuralConflict.Companion.isSuccessorConflict(
                                                pcs, confPcs))
                        .findFirst();
    }

    /**
     * When a conflict in the child list of a node is not possible to resolve, we approximate the
     * conflict by finding the node's matches in the left and right revisions and have them make up
     * the conflict instead. This is a rough estimation, and if they nodes have large child lists it
     * will result in very large conflicts.
     *
     * @param node A node for which the child list could not be constructed.
     * @return An approximated conflict between the left and right matches of the node.
     */
    private StructuralConflict approximateConflict(SpoonNode node) {
        SpoonNode base;
        SpoonNode left;
        SpoonNode right;
        switch (node.getRevision()) {
            case LEFT:
                left = node;
                base = baseLeft.getSrc(left);
                right = baseRight.getDst(base);
                break;
            case RIGHT:
                right = node;
                base = baseRight.getSrc(right);
                left = baseLeft.getDst(base);
                break;
            case BASE:
                base = node;
                left = baseLeft.getDst(node);
                right = baseRight.getDst(node);
                break;
            default:
                throw new ConflictException("Unexpected revision: " + node.getRevision());
        }

        Pair<String, Integer> rawMerge =
                LineBasedMergeKt.lineBasedMerge(base.getElement(), left.getElement(), right.getElement());
        numStructuralConflicts += rawMerge.second;
        return new StructuralConflict(
                base.getElement(), left.getElement(), right.getElement(), rawMerge.first);
    }

    private SpoonNode traverseConflict(
            Pcs<SpoonNode> nextPcs,
            Pcs<SpoonNode> conflicting,
            Map<SpoonNode, Pcs<SpoonNode>> children,
            SporkTree tree) {
        remainingInconsistencies.remove(nextPcs);
        remainingInconsistencies.remove(conflicting);

        SpoonNode next = nextPcs.getSuccessor();
        Arrays.asList(Revision.LEFT, Revision.RIGHT).forEach(tree::addRevision);
        Pcs<SpoonNode> leftPcs = nextPcs.getRevision() == Revision.LEFT ? nextPcs : conflicting;
        Pcs<SpoonNode> rightPcs = leftPcs == nextPcs ? conflicting : nextPcs;
        List<SpoonNode> leftNodes = extractConflictList(leftPcs, children);
        List<SpoonNode> rightNodes = extractConflictList(rightPcs, children);

        Optional<List<SpoonNode>> resolved = tryResolveConflict(leftNodes, rightNodes);

        // if nextPcs happens to be the final PCS of a child list, next may be a virtual node
        next =
                leftNodes.isEmpty()
                        ? rightNodes.get(rightNodes.size() - 1)
                        : leftNodes.get(leftNodes.size() - 1);
        if (resolved.isPresent()) {
            resolved.get().forEach(child -> addChild(tree, build(child)));
        } else {
            numStructuralConflicts++;
            StructuralConflict conflict =
                    new StructuralConflict(
                            leftNodes.stream()
                                    .map(SpoonNode::getElement)
                                    .collect(Collectors.toList()),
                            rightNodes.stream()
                                    .map(SpoonNode::getElement)
                                    .collect(Collectors.toList()));
            // next is used as a dummy node here, so it should not be added to usedNodes
            tree.addChild(new SporkTree(next, contents.get(next), conflict));
        }
        // by convention, build left tree
        return next;
    }

    private void addChild(SporkTree tree, SporkTree child) {
        if (usedNodes.contains(child.getNode())) {
            // if this happens, then there is a duplicate node in the tree, indicating a move
            // conflict
            throw new ConflictException("Move conflict detected");
        }
        tree.addChild(child);
        usedNodes.add(child.getNode());
    }

    /**
     * Scan ahead in the PCS structure to resolve the conflicting children. The conflict must end
     * with a predecessor conflict, or an exception is thrown.
     */
    private List<SpoonNode> extractConflictList(
            Pcs<SpoonNode> pcs, Map<SpoonNode, Pcs<SpoonNode>> siblings) {
        List<SpoonNode> nodes = new ArrayList<>();

        while (true) {
            Set<Pcs<SpoonNode>> conflicts = structuralConflicts.get(pcs);

            if (conflicts != null && !conflicts.isEmpty()) {
                Pcs<SpoonNode> finalPcs = pcs;
                Optional<Pcs<SpoonNode>> predConflict =
                        conflicts.stream()
                                .filter(
                                        confPcs ->
                                                StructuralConflict.Companion.isPredecessorConflict(
                                                        finalPcs, confPcs))
                                .findFirst();

                if (predConflict.isPresent()) {
                    remainingInconsistencies.remove(predConflict.get());
                    return nodes;
                }
            }

            SpoonNode nextNode = pcs.getSuccessor();

            if (nextNode.isEndOfList())
                throw new ConflictException(
                        "Reached the end of the child list without finding a predecessor conflict");

            nodes.add(nextNode);
            pcs = siblings.get(nextNode);
        }
    }
}
