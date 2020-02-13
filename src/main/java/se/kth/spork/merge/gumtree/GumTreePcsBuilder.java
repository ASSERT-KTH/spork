package se.kth.spork.merge.gumtree;

import com.github.gumtreediff.tree.ITree;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;

import java.util.HashSet;
import java.util.Set;

/**
 * Class for building PCS structures from GumTree trees.
 */
public class GumTreePcsBuilder {

    /**
     * This method is borrowed from GumTree's merge branch,
     * see https://github.com/GumTreeDiff/gumtree/blob/fae5832cc60ac12716e472f005880a04354ecbe5/core/src/main/java/com/github/gumtreediff/tree/merge/Pcs.java#L88-L104
     */
    public static Set<Pcs<ITree>> fromGumTree(ITree tree) {
        Set<Pcs<ITree>> result = new HashSet<>();
        for (ITree t: tree.preOrder()) {
            int size = t.getChildren().size();
            for (int i = 0; i < size; i++) {
                ITree c = t.getChild(i);
                if (i == 0)
                    result.add(new Pcs<ITree>(t, null, c));
                result.add(new Pcs<ITree>(t, c, i == (size - 1) ? null : t.getChild(i + 1)));
            }
            if (size == 0)
                result.add(new Pcs<ITree>(t, null, null));
        }
        result.add(new Pcs<ITree>(null, tree, null));
        result.add(new Pcs<ITree>(null, null, tree));
        return result;
    }

    public static Set<Pcs<ITree>> fromGumTree(ITree tree, Revision revision) {
        Set<Pcs<ITree>> pcses = fromGumTree(tree);
        pcses.forEach(pcs -> pcs.setRevision(revision));
        return pcses;
    }
}
