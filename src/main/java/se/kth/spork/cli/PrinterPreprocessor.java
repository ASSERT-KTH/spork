package se.kth.spork.cli;

import se.kth.spork.spoon.ContentConflict;
import se.kth.spork.spoon.RoledValue;
import se.kth.spork.util.LineBasedMerge;
import se.kth.spork.util.Pair;
import spoon.reflect.code.CtComment;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;
import spoon.reflect.visitor.printer.CommentOffset;

import java.util.List;

/**
 * A pre-processor that must run before pretty-printing a merged tree. It does things like embedding conflict values
 * into literals and unsetting the source position of comments (so they get printed).
 *
 * @author Simon Larsén
 */
public class PrinterPreprocessor extends CtScanner {
    public static final String RAW_COMMENT_CONFLICT_KEY = "spork_comment_conflict";

    // the position key is used to put the original source position of an element as metadata
    // this is necessary e.g. for comments as their original source position may cause them not to be printed
    // in a merged tree
    public static final String POSITION_KEY = "spork_position";

    @Override
    public void scan(CtElement element) {
        if (element == null)
            return;

        @SuppressWarnings("unchecked")
        List<ContentConflict> conflicts = (List<ContentConflict>) element.getMetadata(ContentConflict.METADATA_KEY);

        if (conflicts != null) {
            conflicts.forEach(conf -> processConflict(conf, element));
        }

        super.scan(element);
    }

    @Override
    public void visitCtComment(CtComment comment) {
        unsetSourcePosition(comment);
        super.visitCtComment(comment);
    }

    /**
     * Comments that come from a different source file than the node they are attached to are unlikely to actually
     * get printed, as the position relative to the associated node is taken into account by the pretty-printer.
     * Setting the position to {@link SourcePosition#NOPOSITION} causes all comments to be printed before the
     * associated node, but at least they get printed!
     *
     * The reason for this can be found in
     * {@link spoon.reflect.visitor.ElementPrinterHelper#getComments(CtElement, CommentOffset)}.
     */
    private static void unsetSourcePosition(CtElement element) {
        element.putMetadata(POSITION_KEY, element.getPosition());
        element.setPosition(SourcePosition.NOPOSITION);
    }

    /**
     * Process a conflict, and potentially mutate the element with the conflict. For example, values represented
     * as strings may have the conflict embedded directly into the literal.
     *
     * @param conflict A content conflict.
     * @param element The element associated with the conflict.
     */
    private void processConflict(ContentConflict conflict, CtElement element) {
        Object leftVal = conflict.getLeft().getValue();
        Object rightVal = conflict.getRight().getValue();

        String lineSep = System.getProperty("line.separator");
        switch (conflict.getRole()) {
            case NAME:
            case VALUE:
                // embed the conflict directly in the literal value
                String embedded = SporkPrettyPrinter.START_CONFLICT + lineSep
                        + leftVal + lineSep + SporkPrettyPrinter.MID_CONFLICT + lineSep
                        + rightVal + lineSep + SporkPrettyPrinter.END_CONFLICT;
                element.setValueByRole(conflict.getRole(), embedded);
                break;
            case COMMENT_CONTENT:
                String rawLeft = (String) conflict.getLeft().getMetadata(RoledValue.Key.RAW_CONTENT);
                String rawRight = (String) conflict.getRight().getMetadata(RoledValue.Key.RAW_CONTENT);
                String rawBase = conflict.getBase().isPresent() ?
                        (String) conflict.getBase().get().getMetadata(RoledValue.Key.RAW_CONTENT) : "";

                Pair<String, Boolean> rawConflict = LineBasedMerge.merge(rawBase, rawLeft, rawRight);
                assert rawConflict.second : "Comments without conflict should already have been merged";

                element.putMetadata(RAW_COMMENT_CONFLICT_KEY, rawConflict.first);
                break;
        }

    }


}
