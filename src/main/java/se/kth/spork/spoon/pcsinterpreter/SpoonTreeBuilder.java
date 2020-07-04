package se.kth.spork.spoon.pcsinterpreter;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.base3dm.TdmMergeKt;
import se.kth.spork.spoon.*;
import se.kth.spork.spoon.conflict.ContentConflict;
import se.kth.spork.spoon.conflict.ContentConflictHandler;
import se.kth.spork.spoon.conflict.ContentMerger;
import se.kth.spork.spoon.conflict.StructuralConflict;
import se.kth.spork.spoon.matching.SpoonMapping;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.Pair;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.CtModelImpl;
import spoon.reflect.code.CtExpression;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;
import spoon.reflect.factory.ModuleFactory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.printer.CommentOffset;

import java.util.*;

/**
 * Class for building a Spoon tree (i.e. a CtElement) from a {@link SporkTree}.
 *
 * @author Simon Larsén
 */
public class SpoonTreeBuilder {
    public static final String ORIGINAL_NODE_KEY = "spork_original_node";
    public static final String SINGLE_REVISION_KEY = "spork_single_revision";
    // the position key is used to put the original source position of an element as metadata
    // this is necessary e.g. for comments as their original source position may cause them not to be printed
    // in a merged tree
    public static final String POSITION_KEY = "spork_position";

    private final SpoonMapping baseLeft;
    private final SpoonMapping baseRight;
    private int numContentConflicts = 0;

    private final Factory factory;

    // A mapping from the original node to its copy in the merged tree
    private final Map<SpoonNode, SpoonNode> nodes;

    private final ContentMerger contentMerger;

    /**
     * @param baseLeft  The base-to-left tree matching.
     * @param baseRight The base-to-right tree matching.
     * @param oldEnv    Any environment used in the merge. It's needed to copy some values.
     * @param contentConflictHandlers A list of conflict handlers.
     */
    SpoonTreeBuilder(SpoonMapping baseLeft, SpoonMapping baseRight, Environment oldEnv, List<ContentConflictHandler> contentConflictHandlers) {
        nodes = new HashMap<>();
        this.baseLeft = baseLeft;
        this.baseRight = baseRight;
        contentMerger = new ContentMerger(contentConflictHandlers);

        // create a new factory
        Launcher launcher = new Launcher();
        factory = launcher.createFactory();
        Parser.setSporkEnvironment(factory.getEnvironment(), oldEnv.getTabulationSize(), oldEnv.isUsingTabulations());
    }

    /**
     * Some elements are inserted into the Spoon tree based on their position. This applies for example to type members,
     * as Spoon will try to find the appropriate position for them based on position.
     *
     * Comments that come from a different source file than the node they are attached to are also unlikely to actually
     * get printed, as the position relative to the associated node is taken into account by the pretty-printer.
     * Setting the position to {@link SourcePosition#NOPOSITION} causes all comments to be printed before the
     * associated node, but at least they get printed!
     * <p>
     * The reason for this can be found in
     * {@link spoon.reflect.visitor.ElementPrinterHelper#getComments(CtElement, CommentOffset)}.
     * <p>
     * If the position is all ready {@link SourcePosition#NOPOSITION}, then do nothing.
     */
    private static void unsetSourcePosition(CtElement element) {
        if (!element.getPosition().isValidPosition())
            return;

        element.putMetadata(POSITION_KEY, element.getPosition());
        element.setPosition(SourcePosition.NOPOSITION);
    }

    /**
     * Create a shallow copy of a tree.
     *
     * @param tree A tree to copy.
     * @return A shallow copy of the input tree.
     */
    private CtElement shallowCopyTree(CtElement tree, Factory factory) {
        if (tree instanceof ModuleFactory.CtUnnamedModule) {
            return factory.Module().getUnnamedModule();
        } else if (tree instanceof CtModelImpl.CtRootPackage) {
            return factory.Package().getRootPackage();
        }

        // FIXME This is super inefficient, cloning the whole tree just to delete all its children
        CtElement treeCopy = tree.clone();
        for (CtElement child : treeCopy.getDirectChildren()) {
            child.delete();
        }
        treeCopy.setFactory(factory);
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
     * @return The amount of conflicts.
     */
    public int numContentConflicts() {
        return numContentConflicts;
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
        Optional<CtElement> mergeParentOpt = Optional.ofNullable(
                origRootNode == NodeFactory.ROOT ? null : nodes.get(origRootNode).getElement());
        CtElement mergeTree;

        if (sporkChild.isSingleRevisionSubtree()) {
            mergeTree = originalTree.clone();
            mergeTree.putMetadata(SINGLE_REVISION_KEY, sporkChild.getSingleRevision());
        } else {
            Pair<RoledValues, List<ContentConflict>> mergedContent =
                    contentMerger.mergedContent(sporkChild.getContent());

            mergeTree = shallowCopyTree(originalTree, factory);
            mergedContent.first.forEach(rv -> mergeTree.setValueByRole(rv.getRole(), rv.getValue()));
            if (!mergedContent.second.isEmpty()) {
                // at least one conflict was not resolved
                mergeTree.putMetadata(ContentConflict.METADATA_KEY, mergedContent.second);
                numContentConflicts += mergedContent.second.size();
            }
        }

        // adjust metadata for the merge tree
        Map<String, Object> metadata = new HashMap<>(mergeTree.getAllMetadata());
        metadata.remove(NodeFactory.WRAPPER_METADATA);
        metadata.put(ORIGINAL_NODE_KEY, originalTree);
        mergeTree.setAllMetadata(metadata);

        if (mergeParentOpt.isPresent()) {
            CtElement mergeParent = mergeParentOpt.get();
            CtRole mergeTreeRole = resolveRole(origTreeNode);
            Object inserted = withSiblings(originalTree, mergeParent, mergeTree, mergeTreeRole);

            if (mergeTreeRole == CtRole.TYPE_MEMBER || mergeTreeRole == CtRole.COMMENT) {
                unsetSourcePosition(mergeTree);
            }

            if (isVarKeyword(mergeTree) && mergeParent instanceof CtParameterReference && mergeTreeRole == CtRole.TYPE) {
                // we skip this case, because  for some reason, when it comes to parameter references, Spoon sets
                // the type to null if it's actually "var"
            } else {
                mergeParent.setValueByRole(mergeTreeRole, inserted);
            }
        }

        SpoonNode mergeNode;
        if (mergeParentOpt.isPresent()) {
            // NOTE: Super important that the parent of the merge tree is set no matter what, as wrapping a spoon CtElement
            // in a SpoonNode requires access to its parent.
            mergeTree.setParent(mergeParentOpt.get());
            mergeNode = NodeFactory.wrap(mergeTree);
        } else {
            // if the merge tree has no parent, then its parent is the virtual root
            mergeNode = NodeFactory.forceWrap(mergeTree, NodeFactory.ROOT);
        }
        nodes.put(origTreeNode, mergeNode);

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
        final CtElement dummy = conflict.left.size() > 0 ? conflict.left.get(0) : conflict.right.get(0);

        CtElement mergeParent = nodes.get(parent).getElement();

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

        switch ((Revision) tree.getMetadata(TdmMergeKt.REV)) {
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
