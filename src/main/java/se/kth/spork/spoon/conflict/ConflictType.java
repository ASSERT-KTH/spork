package se.kth.spork.spoon.conflict;

/**
 * Enum indicating a kind of structural conflict.
 *
 * @author Simon Larsén
 */
public enum ConflictType {
    INSERT_INSERT,
    INSERT_DELETE,
    DELETE_DELETE,
    DELETE_EDIT,
    MOVE;
}
