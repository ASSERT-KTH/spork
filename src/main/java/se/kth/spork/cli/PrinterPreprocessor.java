package se.kth.spork.cli;

import se.kth.spork.spoon.ContentConflict;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.List;
import java.util.Optional;

/**
 * A pre-processor that must run before pretty-printing a merged tree. It does things like embedding conflict values
 * into literals and unsetting the source position of comments (so they get printed).
 *
 * @author Simon Larsén
 */
public class PrinterPreprocessor extends CtScanner {
    public static final String RAW_COMMENT_CONFLICT = "spork_comment_conflict";

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

        Optional<Object> processed = Optional.empty();

        switch (conflict.getRole()) {
            case NAME:
            case VALUE:
                // embed the conflict directly in the literal value
                processed = Optional.of("\n" + SporkPrettyPrinter.START_CONFLICT + "\n"
                        + leftVal + "\n" + SporkPrettyPrinter.MID_CONFLICT + "\n"
                        + rightVal + "\n" + SporkPrettyPrinter.END_CONFLICT + "\n");
                break;
        }

        processed.ifPresent(o -> element.setValueByRole(conflict.getRole(), o));
    }
}
