package se.kth.spork.util

import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.SequenceComparator
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeChunk
import org.eclipse.jgit.merge.MergeResult
import se.kth.spork.spoon.printer.SourceExtractor
import se.kth.spork.spoon.printer.SporkPrettyPrinter
import spoon.reflect.declaration.CtElement
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
        return Pair(if (left.isEmpty()) right else left, 0)
    }

    val baseRaw = RawText(base.toByteArray())
    val leftRaw = RawText(left.toByteArray())
    val rightRaw = RawText(right.toByteArray())

    val merge = MergeAlgorithm()
    val res: MergeResult<RawText> = merge.merge(
        object : SequenceComparator<RawText>() {
            override fun equals(lhs: RawText, lhsIdx: Int, rhs: RawText, rhsIdx: Int) =
                lhs.getString(lhsIdx) == rhs.getString(rhsIdx)

            override fun hash(s: RawText, i: Int) = Objects.hash(s.getString(i))
        },
        baseRaw,
        leftRaw,
        rightRaw,
    )

    val it: Iterator<MergeChunk> = res.iterator()
    val lines: MutableList<String> = ArrayList()
    var inConflict = false
    var numConflicts = 0
    while (it.hasNext()) {
        val chunk = it.next()
        val seq = res.sequences[chunk.sequenceIndex]

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
        } else if (chunk.conflictState
            == MergeChunk.ConflictState.BASE_CONFLICTING_RANGE
        ) {
            continue
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

    return Pair(lines.joinToString("\n"), numConflicts)
}

/**
 * Merge three revisions of a Spoon element using line-based merge.
 *
 * @param base The base revision.
 * @param left The left revision.
 * @param right The right revision.
 * @return A pair containing the merge and the amount of conflicts.
 */
fun lineBasedMerge(base: CtElement, left: CtElement, right: CtElement): Pair<String, Int> {
    val baseSource = SourceExtractor.getOriginalSource(base)
    val leftSource = SourceExtractor.getOriginalSource(left)
    val rightSource = SourceExtractor.getOriginalSource(right)
    return lineBasedMerge(baseSource, leftSource, rightSource)
}
