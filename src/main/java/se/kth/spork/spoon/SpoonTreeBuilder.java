package se.kth.spork.spoon;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.base3dm.TdmMerge;
import se.kth.spork.util.Pair;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtParameterReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.*;

/**
 * Class for building a Spoon tree (i.e. a CtElement) from a {@link SporkTree}.
 *
 * @author Simon Larsén
 */
public class SpoonTreeBuilder {
    public static final String ORIGINAL_NODE_KEY = "spork_original_node";
    public static final String SINGLE_REVISION_KEY = "spork_single_revision";

    private SpoonMapping baseLeft;
    private SpoonMapping baseRight;
    private boolean hasContentConflict = false;

    // A mapping from the original node to its copy in the merged tree
    private Map<SpoonNode, SpoonNode> nodes;

    /**
     * @param baseLeft  The base-to-left tree matching.
     * @param baseRight The base-to-right tree matching.
     */
    SpoonTreeBuilder(SpoonMapping baseLeft, SpoonMapping baseRight) {
        nodes = new HashMap<>();
        this.baseLeft = baseLeft;
        this.baseRight = baseRight;
    }

    /**
     * Create a shallow copy of a tree.
     *
     * @param tree A tree to copy.
     * @return A shallow copy of the input tree.
     */
    private static CtElement shallowCopyTree(CtElement tree) {
        // FIXME This is super inefficient, cloning the whole tree just to delete all its children
        CtElement treeCopy = tree.clone();
        for (CtElement child : treeCopy.getDirectChildren()) {
            child.delete();
        }
        return treeCopy;
    }

    /**
     * Build the children of the provided root.
     *
     * @param root The root of a {@link SporkTree}
     * @return The last Spoon tree in the built child list. This may be null!
     */
    public CtElement build(SporkTree root) {
        CtElement lastChild = null;

        for (SporkTree child : root.getChildren()) {
            Optional<StructuralConflict> conflict = child.getStructuralConflict();
            lastChild = conflict
                    .map(structuralConflict -> visitConflicting(root.getNode(), structuralConflict))
                    .orElseGet(() -> visit(root, child));

            if (root.getNode() == NodeFactory.ROOT || !child.isSingleRevisionSubtree())
                build(child);
        }

        return lastChild;
    }

    /**
     * @return true if a content conflict was encountered when building the tree.
     */
    public boolean hasContentConflict() {
        return hasContentConflict;
    }

    /**
     * Visit a node an merge it. Note that both the node being visited, and its parent, are the original nodes from
     * the input trees.
     *
     * @param sporkParent A wrapper around the current node's parent.
     * @param sporkChild  A wrapper around the current node being visited.
     */
    private CtElement visit(SporkTree sporkParent, SporkTree sporkChild) {
        SpoonNode origRootNode = sporkParent.getNode();
        SpoonNode origTreeNode = sporkChild.getNode();
        CtElement originalTree = origTreeNode.getElement();
        CtElement mergeParent = origRootNode == NodeFactory.ROOT ? null : nodes.get(origRootNode).getElement();
        CtElement mergeTree;

        if (sporkChild.isSingleRevisionSubtree()) {
            mergeTree = originalTree.clone();
            mergeTree.putMetadata(SINGLE_REVISION_KEY, sporkChild.getSingleRevision());
        } else {
            Pair<RoledValues, List<ContentConflict>> mergedContent =
                    ContentMerger.mergedContent(sporkChild.getContent());

            mergeTree = shallowCopyTree(originalTree);
            mergedContent.first.forEach(rv -> mergeTree.setValueByRole(rv.getRole(), rv.getValue()));
            if (!mergedContent.second.isEmpty()) {
                // at least one conflict was not resolved
                mergeTree.putMetadata(ContentConflict.METADATA_KEY, mergedContent.second);
                hasContentConflict = true;
            }
        }

        // adjust metadata for the merge tree
        Map<String, Object> metadata = new HashMap<>(mergeTree.getAllMetadata());
        metadata.remove(NodeFactory.WRAPPER_METADATA);
        metadata.put(ORIGINAL_NODE_KEY, originalTree);
        mergeTree.setAllMetadata(metadata);

        if (mergeParent != null) {
            CtRole mergeTreeRole = resolveRole(origTreeNode);
            Object inserted = withSiblings(originalTree, mergeParent, mergeTree, mergeTreeRole);

            if (isVarKeyword(mergeTree) && mergeParent instanceof CtParameterReference && mergeTreeRole == CtRole.TYPE) {
                // we skip this case, because  for some reason, when it comes to parameter references, Spoon sets
                // the type to null if it's actually "var"
            } else {
                mergeParent.setValueByRole(mergeTreeRole, inserted);
            }
        }

        nodes.put(origTreeNode, NodeFactory.wrap(mergeTree));

        return mergeTree;
    }

    /**
     * Visit the root nodes of a conflict. Note that the children of these nodes are not visited
     * by this method.
     *
     * @param parent   The parent node of the conflict.
     * @param conflict The current structural conflict.
     */
    private CtElement visitConflicting(SpoonNode parent, StructuralConflict conflict) {
        CtElement mergeParent = nodes.get(parent).getElement();
        CtElement dummy = conflict.left.size() > 0 ? conflict.left.get(0) : conflict.right.get(0);

        dummy.putMetadata(StructuralConflict.METADATA_KEY, conflict);
        SpoonNode dummyNode = NodeFactory.wrap(dummy);
        CtRole role = resolveRole(dummyNode);

        Object inserted = withSiblings(dummy, mergeParent, dummy, role);
        dummy.delete();
        mergeParent.setValueByRole(role, inserted);

        return dummy;
    }

    private boolean isVarKeyword(CtElement mergeTree) {
        return mergeTree instanceof CtTypeReference
                && ((CtTypeReference<?>) mergeTree).getSimpleName().equals("var");
    }

    private Object withSiblings(
            CtElement originalTree,
            CtElement mergeParent,
            CtElement mergeTree,
            CtRole mergeTreeRole) {
        Object siblings = mergeParent.getValueByRole(mergeTreeRole);
        Object inserted;

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
            inserted = mutableCurrent;
        } else if (siblings instanceof Map) {
            inserted = resolveAnnotationMap(mergeTree, (Map<?, ?>) siblings, originalTree);
        } else {
            inserted = mergeTree;
        }

        return inserted;
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
     * @param mergeTree    The tree node currently being merged, to be inserted as a value among siblings.
     * @param siblings     A potentially empty map of annotation keys->values currently in the merge tree's parent's
     *                     children, i.e. the siblings of the current mergeTree.
     * @param originalTree The tree from which mergeTree was copied.
     * @return A map representing the key/value pairs of an annotation, wich mergeTree inserted among its siblings.
     */
    private Map<?, ?> resolveAnnotationMap(
            CtElement mergeTree, Map<?, ?> siblings, CtElement originalTree) {

        Map<Object, Object> mutableCurrent = new TreeMap<>(siblings);

        CtAnnotation<?> annotation = (CtAnnotation<?>) originalTree.getParent();
        Optional<Map.Entry<String, CtExpression>> originalEntry = annotation
                .getValues().entrySet().stream().filter(
                        entry -> entry.getValue() == originalTree).findFirst();

        if (!originalEntry.isPresent()) {
            throw new IllegalStateException(
                    "Internal error: unable to find key for annotation value " + mergeTree);
        }

        mutableCurrent.put(originalEntry.get().getKey(), mergeTree);

        return mutableCurrent;
    }
}
