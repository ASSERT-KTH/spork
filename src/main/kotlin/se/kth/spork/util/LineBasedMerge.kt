package se.kth.spork.util

import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.diff.SequenceComparator
import org.eclipse.jgit.merge.MergeChunk
import se.kth.spork.spoon.printer.SporkPrettyPrinter
import spoon.reflect.declaration.CtElement
import se.kth.spork.spoon.printer.SourceExtractor
import java.util.ArrayList
import java.util.Objects

/**
 * Line-based merge implementation using JGit.
 *
 * @param base The base revision.
 * @param left The left revision.
 * @param right The right revision.
 * @return A pair containing the merge and the amount of conflicts.
 */
fun lineBasedMerge(base: String, left: String, right: String): Pair<String, Int> {
    if (base.isEmpty() && (left.isEmpty() || right.isEmpty())) {
        // For some reason, this merge implementation reports a conflict on pure additions.
        // This is an easy fix for that. See #144 for details.
        return Pair.of(if (left.isEmpty()) right else left, 0)
    }
    val baseRaw = RawText(base.toByteArray())
    val leftRaw = RawText(left.toByteArray())
    val rightRaw = RawText(right.toByteArray())
    val merge = MergeAlgorithm()
    val res = merge.merge(
        object : SequenceComparator<RawText>() {
            override fun equals(s: RawText, i: Int, s1: RawText, i1: Int): Boolean {
                return s.getString(i) == s1.getString(i1)
            }

            override fun hash(s: RawText, i: Int): Int {
                return Objects.hash(s.getString(i))
            }
        },
        baseRaw,
        leftRaw,
        rightRaw
    )
    val it: Iterator<MergeChunk> = res.iterator()
    val seqs = res.sequences
    val lines: MutableList<String> = ArrayList()
    var inConflict = false
    var numConflicts = 0
    while (it.hasNext()) {
        val chunk = it.next()
        val seq = seqs[chunk.sequenceIndex]
        if (chunk.conflictState == MergeChunk.ConflictState.FIRST_CONFLICTING_RANGE) {
            numConflicts++
            inConflict = true
            lines.add(SporkPrettyPrinter.START_CONFLICT)
        } else if (chunk.conflictState
            == MergeChunk.ConflictState.NEXT_CONFLICTING_RANGE
        ) {
            if (!inConflict) {
                lines.add(SporkPrettyPrinter.START_CONFLICT)
                inConflict = true
            }
            lines.add(SporkPrettyPrinter.MID_CONFLICT)
        }
        for (i in chunk.begin until chunk.end) {
            lines.add(seq.getString(i))
        }
        if (chunk.conflictState == MergeChunk.ConflictState.NEXT_CONFLICTING_RANGE) {
            lines.add(SporkPrettyPrinter.END_CONFLICT)
            inConflict = false
        }
    }
    if (inConflict) {
        lines.add(SporkPrettyPrinter.MID_CONFLICT)
        lines.add(SporkPrettyPrinter.END_CONFLICT)
    }
    return Pair(java.lang.String.join("\n", lines), numConflicts)
}

/**
 * Merge three revisions of a Spoon element using line-based merge.
 *
 * @param base The base revision.
 * @param left The left revision.
 * @param right The right revision.
 * @return A pair containing the merge and the amount of conflicts.
 */
fun lineBasedMerge(base: CtElement?, left: CtElement?, right: CtElement?): Pair<String, Int> {
    val baseSource = SourceExtractor.getOriginalSource(base)
    val leftSource = SourceExtractor.getOriginalSource(left)
    val rightSource = SourceExtractor.getOriginalSource(right)
    return lineBasedMerge(baseSource, leftSource, rightSource)
}