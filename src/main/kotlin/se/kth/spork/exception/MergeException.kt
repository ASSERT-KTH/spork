package se.kth.spork.exception

/**
 * Generic exception thrown when there is a critical error in the merge.
 *
 * @author Simon Larsén
 */
class MergeException @JvmOverloads constructor(s: String, t: Throwable? = null) : SporkException(s, t)
