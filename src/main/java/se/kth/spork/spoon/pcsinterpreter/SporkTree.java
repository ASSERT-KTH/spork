package se.kth.spork.spoon.pcsinterpreter;

import java.util.*;
import se.kth.spork.base3dm.Content;
import se.kth.spork.base3dm.Revision;
import se.kth.spork.spoon.conflict.StructuralConflict;
import se.kth.spork.spoon.wrappers.NodeFactory;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.wrappers.SpoonNode;
import spoon.reflect.CtModelImpl;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.ModuleFactory;

/**
 * A Spork tree is an intermediate representation used to bridge conversion from PCS to Spoon. It is
 * essentially a greatly simplified AST that also offers some support for representing structural
 * conflicts as well as keeping track of which revisions it consists of.
 *
 * @author Simon Larsén
 */
public class SporkTree {
    private SpoonNode node;
    private Optional<StructuralConflict> structuralConflict;
    private List<SporkTree> children;
    private Set<Revision> revisions;
    private Set<Content<SpoonNode, RoledValues>> content;

    public SporkTree(
            SpoonNode node,
            Set<Content<SpoonNode, RoledValues>> content,
            StructuralConflict structuralConflict) {
        this.node = node;
        this.content = content;
        this.structuralConflict = Optional.ofNullable(structuralConflict);
        children = new ArrayList<>();
        revisions = new TreeSet<>();

        if (node != NodeFactory.INSTANCE.getVirtualRoot()) {
            revisions.add(node.getRevision());
        }
        content.stream().map(Content::getRevision).forEach(revisions::add);
    }

    public SporkTree(SpoonNode node, Set<Content<SpoonNode, RoledValues>> content) {
        this(node, content, null);
    }

    public boolean hasStructuralConflict() {
        return structuralConflict.isPresent();
    }

    public SpoonNode getNode() {
        return node;
    }

    public Optional<StructuralConflict> getStructuralConflict() {
        return structuralConflict;
    }

    public void addChild(SporkTree child) {
        revisions.addAll(child.revisions);
        children.add(child);
    }

    public void setRevisions(Collection<Revision> revisions) {
        this.revisions.clear();
        this.revisions.addAll(revisions);
    }

    public Set<Revision> getRevisions() {
        return Collections.unmodifiableSet(revisions);
    }

    public List<SporkTree> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * @return True if the subtree consists of only a single revision, AND the subtree is not rooted
     *     in the unnamed module or the root package. These should never be considered single
     *     revision as they must be replaced with new elements.
     */
    public boolean isSingleRevisionSubtree() {
        CtElement element = node.getElement();
        return !(element instanceof ModuleFactory.CtUnnamedModule
                        || element instanceof CtModelImpl.CtRootPackage)
                && revisions.size() == 1;
    }

    public Revision getSingleRevision() {
        if (revisions.size() != 1) {
            throw new IllegalStateException("Not a single revision subtree");
        }
        return revisions.iterator().next();
    }

    public void addRevision(Revision revision) {
        revisions.add(revision);
    }

    public Set<Content<SpoonNode, RoledValues>> getContent() {
        return content;
    }
}
