package se.kth.spork.exception

/**
 * Thrown when Spork encounters a conflict that cannot be handled properly.
 *
 * @author Simon Larsén
 */
class ConflictException @JvmOverloads constructor(s: String, t: Throwable? = null) : SporkException(s, t)
