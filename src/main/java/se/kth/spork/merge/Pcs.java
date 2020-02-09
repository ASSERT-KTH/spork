package se.kth.spork.merge;

/*
 * This file is part of GumTree.
 *
 * GumTree is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GumTree is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with GumTree.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2016 Jean-Rémy Falleri <jr.falleri@gmail.com>
 * Copyright 2016 Floréal Morandat <florealm@gmail.com>
 */

import com.github.gumtreediff.tree.ITree;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * This file is adapted from GumTree, see the original license statement above. The only non-trivial thing that
 * is borrowed is the fromTree method, everything else is either trivial or original.
 *
 * Representation of a Parent/Child/Successor triple. Note that the revision does not (and should not) impact hashing
 * or equality, it is just there as metainformation.
 */
public class Pcs {
    private ITree root;
    private ITree predecessor;
    private ITree successor;
    private Revision revision;

    public Pcs(ITree root, ITree predecessor, ITree successor) {
        this.root = root;
        this.predecessor = predecessor;
        this.successor = successor;
        revision = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Pcs pcs = (Pcs) o;
        return Objects.equals(root, pcs.root)
                && Objects.equals(predecessor, pcs.predecessor)
                && Objects.equals(successor, pcs.successor);
    }

    @Override
    public String toString() {
        return "PCS(" + (revision != null ? revision + "," : "")
                + toShortString(root) + ","
                + toShortString(predecessor) + ","
                + toShortString(successor) + ")";
    }

    public ITree getRoot() {
        return root;
    }

    public ITree getPredecessor() {
        return predecessor;
    }

    public ITree getSuccessor() {
        return successor;
    }

    public Revision getRevision() {
        return revision;
    }

    public void setRevision(Revision revision) {
        this.revision = revision;
    }

    private static String toShortString(ITree tree) {
        return tree == null ? "null" : tree.toShortString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, predecessor, successor);
    }

    /**
     * This method is borrowed from GumTree's merge branch,
     * see https://github.com/GumTreeDiff/gumtree/blob/fae5832cc60ac12716e472f005880a04354ecbe5/core/src/main/java/com/github/gumtreediff/tree/merge/Pcs.java#L88-L104
     */
    public static Set<Pcs> fromTree(ITree tree) {
        Set<Pcs> result = new HashSet<>();
        for (ITree t: tree.preOrder()) {
            int size = t.getChildren().size();
            for (int i = 0; i < size; i++) {
                ITree c = t.getChild(i);
                if (i == 0)
                    result.add(new Pcs(t, null, c));
                result.add(new Pcs(t, c, i == (size - 1) ? null : t.getChild(i + 1)));
            }
            if (size == 0)
                result.add(new Pcs(t, null, null));
        }
        result.add(new Pcs(null, tree, null));
        result.add(new Pcs(null, null, tree));
        return result;
    }

    public static Set<Pcs> fromTree(ITree tree, Revision revision) {
        Set<Pcs> pcses = fromTree(tree);
        pcses.forEach(pcs -> pcs.revision = revision);
        return pcses;
    }
}