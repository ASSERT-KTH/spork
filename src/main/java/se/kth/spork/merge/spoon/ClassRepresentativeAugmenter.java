package se.kth.spork.merge.spoon;

import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.Map;

/**
 * A scanner that conservatively expands the class representatives mapping with matches from a left-to-right
 * tree matching. If a node in the left tree is not mapped to base (i.e. self-mapped in the class
 * representatives map), but it is matched with some node in right, then the node in right is mapped
 * to the node in left iff their parents are already mapped, and the node in right is also self-mapped.
 * The node in left remains self-mapped.
 *
 * This must be done by traversing the left tree top-down to allow augmenting mappings to propagate. For
 * example, if both the left and the right revision have added identical methods, then their declarations
 * will be mapped first, and then their contents will be mapped recursively (which is OK as the
 * declarations are now mapped). If one would have started with matches in the bodies of the methods,
 * then these would not be added to the class representatives map as the declarations (i.e. parents)
 * would not yet be mapped.
 *
 * The reason for this conservative use of the left-to-right matchings is that there is otherwise a high
 * probability of unwanted matches. For example, if the left revision adds a parameter to some method,
 * and the right revision adds an identical parameter to another method, then these may be matched,
 * even though they are not related. If that match is put into the class representatives map, there
 * may be some really strange effects on the merge process.
 *
 * @author Simon Larsén
 */
public class ClassRepresentativeAugmenter extends CtScanner {
    private SpoonMapping leftRightMatch;
    private Map<SpoonNode, SpoonNode> classRepMap;

    /**
     * @param classRepMap The class representatives map, initialized with left-to-base and right-to-base mappings.
     * @param leftRightMatch A tree matching between the left and right revisions, where the left revision is the
     *                       source and the right revision the destination.
     */
    public ClassRepresentativeAugmenter(Map<SpoonNode, SpoonNode> classRepMap, SpoonMapping leftRightMatch) {
        this.classRepMap = classRepMap;
        this.leftRightMatch = leftRightMatch;
    }

    /**
     *
     * @param element An element from the left revision.
     */
    @Override
    public void scan(CtElement element) {
        if (element == null)
            return;

        assert element.getMetadata(TdmMerge.REV) == Revision.LEFT;

        SpoonNode left = NodeFactory.wrap(element);
        if (classRepMap.get(left) == left) {
            SpoonNode right = leftRightMatch.getDst(left);

            if (right != null && classRepMap.get(right) == right) {
                SpoonNode rightParentClassRep = classRepMap.get(right.getParent());
                SpoonNode leftParentClassRep = classRepMap.get(left.getParent());

                if (leftParentClassRep == rightParentClassRep) {
                    // map right to left
                    classRepMap.put(right, left);
                }
            }
        }
        super.scan(element);
    }
}
