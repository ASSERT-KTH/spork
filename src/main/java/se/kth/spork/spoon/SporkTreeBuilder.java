package se.kth.spork.spoon;

import se.kth.spork.base3dm.*;
import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class for building a {@link SporkTree} from a merged {@link ChangeSet}.
 *
 * @author Simon Larsén
 */
class SporkTreeBuilder {

    private final Map<SpoonNode, Map<SpoonNode, Pcs<SpoonNode>>> rootToChildren;
    private final Map<Pcs<SpoonNode>, Set<Pcs<SpoonNode>>> structuralConflicts;
    private final Map<SpoonNode, Set<Content<SpoonNode, RoledValues>>> contents;
    private boolean hasStructuralConflicts;

    // keeps track of which nodes have been added to the tree all ready
    // if any node is added twice, there's an unresolved move conflict
    private Set<SpoonNode> usedNodes;

    /**
     * Create a builder.
     *
     * @param delta A merged change set.
     */
    public SporkTreeBuilder(ChangeSet<SpoonNode, RoledValues> delta) {
        this.rootToChildren = buildRootToChildren(delta.getPcsSet());
        structuralConflicts = delta.getStructuralConflicts();
        contents = delta.getContents();
        hasStructuralConflicts = false;
        usedNodes = new HashSet<>();
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

    /**
     * @return true iff conflicts were encountered at some point when building a tree.
     */
    public boolean hasStructuralConflict() {
        return hasStructuralConflicts;
    }

    /**
     * Build a subtree of the {@link ChangeSet} contained in this builder. The {@link SporkTree} that's returned
     * contains all information required to build the final Spoon tree, including structural conflict information.
     *
     * @param currentRoot The node from which to build the subtree.
     * @return A {@link SporkTree} rooted in the provided root node.
     */
    public SporkTree build(SpoonNode currentRoot) {
        Map<SpoonNode, Pcs<SpoonNode>> children = rootToChildren.get(currentRoot);

        SpoonNode next = NodeFactory.startOfChildList(currentRoot);

        Set<Content<SpoonNode, RoledValues>> currentContent = contents.getOrDefault(currentRoot, Collections.emptySet());
        SporkTree tree = new SporkTree(currentRoot, currentContent);

        if (children == null) // leaf node
            return tree;

        while (true) {
            Pcs<SpoonNode> nextPcs = children.get(next);
            tree.addRevision(nextPcs.getRevision());

            next = nextPcs.getSuccessor();
            if (next.isEndOfList()) {
                break;
            }

            Set<Pcs<SpoonNode>> conflicts = structuralConflicts.get(nextPcs);
            Optional<Pcs<SpoonNode>> successorConflict = conflicts == null ? Optional.empty() :
                    conflicts.stream().filter(confPcs ->
                            StructuralConflict.isSuccessorConflict(nextPcs, confPcs)).findFirst();

            // successor conflicts mark the start of a conflict, any other conflict is to be ignored
            if (successorConflict.isPresent()) {
                Pcs<SpoonNode> conflicting = successorConflict.get();
                next = traverseConflict(nextPcs, conflicting, children, tree);
            } else {
                addChild(tree, build(next));
            }
        }

        return tree;
    }

    private SpoonNode traverseConflict(
            Pcs<SpoonNode> nextPcs,
            Pcs<SpoonNode> conflicting,
            Map<SpoonNode, Pcs<SpoonNode>> children,
            SporkTree tree) {
        SpoonNode next = nextPcs.getSuccessor();
        Arrays.asList(Revision.LEFT, Revision.RIGHT).forEach(tree::addRevision);
        Pcs<SpoonNode> leftPcs = nextPcs.getRevision() == Revision.LEFT ? nextPcs : conflicting;
        Pcs<SpoonNode> rightPcs = leftPcs == nextPcs ? conflicting : nextPcs;
        List<SpoonNode> leftNodes = extractConflictList(leftPcs, children);
        List<SpoonNode> rightNodes = extractConflictList(rightPcs, children);

        Optional<List<SpoonNode>> resolved = tryResolveConflict(leftNodes, rightNodes);
        if (resolved.isPresent()) {
            resolved.get().forEach(child ->
                    addChild(tree, build(child))
            );
        } else {
            hasStructuralConflicts = true;
            StructuralConflict conflict = new StructuralConflict(
                    leftNodes.stream().map(SpoonNode::getElement).collect(Collectors.toList()),
                    rightNodes.stream().map(SpoonNode::getElement).collect(Collectors.toList())
            );
            // next is used as a dummy node here, so it should not be added to usedNodes
            tree.addChild(new SporkTree(next, contents.get(next), conflict));
        }
        // by convention, build left tree
        return leftNodes.isEmpty() ? next : leftNodes.get(leftNodes.size() - 1);
    }

    private void addChild(SporkTree tree, SporkTree child) {
        if (usedNodes.contains(child.getNode())) {
            // if this happens, then there is a duplicate node in the tree, indicating a move conflict
            throw new IllegalStateException("Move conflict detected");
        }
        tree.addChild(child);
        usedNodes.add(child.getNode());
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
                        .filter(confPcs -> StructuralConflict.isPredecessorConflict(finalPcs, confPcs)).findFirst();

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
}
