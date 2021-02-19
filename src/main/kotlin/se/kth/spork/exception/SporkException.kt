package se.kth.spork.exception

import java.lang.RuntimeException

/**
 * Base exception for Spork exceptions.
 *
 * @author Simon Larsén
 */
abstract class SporkException @JvmOverloads constructor(s: String, t: Throwable? = null) : RuntimeException(s, t)
