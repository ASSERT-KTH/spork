package se.kth.spork.merge.spoon;

/**
 * A simple class that provides some information on a conflict. Meant to be put as metadata on certain
 * key nodes in a conflict (such as the first node of the left part, the first node of the right part,
 * etc).
 *
 * Note that {@link ConflictInfo#leftSize} and {@link ConflictInfo#rightSize} specify the amount of root
 * nodes in either part of the conflict. That is to say, it does not account for any children of the
 * conflicting nodes.
 *
 * @author Simon Larsén
 */
public class ConflictInfo {
    public static final String CONFLICT_METADATA = "SPORK_CONFLICT";
    public final int leftSize;
    public final int rightSize;
    public final ConflictMarker marker;

    /**
     * @param leftSize The amount of root nodes on the left part of the conflict.
     * @param rightSize The amount of root nodes in the right part of the conflict.
     * @param marker Marker designating what part of the conflict this info is attached to.
     */
    public ConflictInfo(int leftSize, int rightSize, ConflictMarker marker) {
        this.leftSize = leftSize;
        this.rightSize = rightSize;
        this.marker = marker;
    }

    /**
     * A marker that denotes which part of the conflict a {@link ConflictInfo} instance is attached to.
     */
    public enum ConflictMarker {
        LEFT_START, LEFT_END, RIGHT_START, RIGHT_END
    }
}
