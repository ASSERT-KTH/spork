package se.kth.spork.util;

import se.kth.spork.cli.SporkPrettyPrinter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.SequenceComparator;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

/**
 * Line-based merge implementation using JGit.
 *
 * @author Simon Larsén
 */
public class LineBasedMerge {

    /**
     * Merge three revisions of a string using line-based merge.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @return A line-based merge, with conflict markers in case of conflict.
     */
    public static String merge(String base, String left, String right) {
        RawText baseRaw = new RawText(base.getBytes());
        RawText leftRaw = new RawText(left.getBytes());
        RawText rightRaw = new RawText(right.getBytes());

        MergeAlgorithm merge = new MergeAlgorithm();
        MergeResult<RawText> res = merge.merge(new SequenceComparator<RawText>() {
            @Override
            public boolean equals(RawText s, int i, RawText s1, int i1) {
                return s.getString(i).equals(s1.getString(i));
            }

            @Override
            public int hash(RawText s, int i) {
                return Objects.hash(s.getString(i));
            }
        }, baseRaw, leftRaw, rightRaw);

        Iterator<MergeChunk> it = res.iterator();
        List<RawText> seqs = res.getSequences();
        List<String> lines = new ArrayList<>();
        boolean inConflict = false;

        while (it.hasNext()) {
            MergeChunk chunk = it.next();
            RawText seq = seqs.get(chunk.getSequenceIndex());

            if (chunk.getConflictState() == MergeChunk.ConflictState.FIRST_CONFLICTING_RANGE) {
                inConflict = true;
                lines.add(SporkPrettyPrinter.START_CONFLICT);
            } else if (chunk.getConflictState() == MergeChunk.ConflictState.NEXT_CONFLICTING_RANGE) {
                if (!inConflict) {
                    lines.add(SporkPrettyPrinter.START_CONFLICT);
                    inConflict = true;
                }

                lines.add(SporkPrettyPrinter.MID_CONFLICT);
            }


            for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
                lines.add(seq.getString(i));
            }

            if (chunk.getConflictState() == MergeChunk.ConflictState.NEXT_CONFLICTING_RANGE) {
                lines.add(SporkPrettyPrinter.END_CONFLICT);
                inConflict = false;
            }

        }

        if (inConflict) {
            lines.add(SporkPrettyPrinter.MID_CONFLICT);
            lines.add(SporkPrettyPrinter.END_CONFLICT);
        }

        return String.join("\n", lines);
    }
}