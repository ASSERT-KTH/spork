package se.kth.spork.cli;

import se.kth.spork.merge.spoon.ConflictInfo;
import spoon.compiler.Environment;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.PrinterHelper;
import spoon.reflect.visitor.TokenWriter;

public class SporkPrettyPrinter extends DefaultJavaPrettyPrinter {
    public static final String START_CONFLICT = "<<<<<<< LEFT";
    public static final String MID_CONFLICT = "=======";
    public static final String END_CONFLICT = ">>>>>>> RIGHT";

    /**
     * Creates a new code generator visitor.
     *
     * @param env
     */
    public SporkPrettyPrinter(Environment env) {
        super(env);
    }

    @Override
    public SporkPrettyPrinter scan(CtElement e) {
        if (e == null || !e.getMetadataKeys().contains(ConflictInfo.CONFLICT_METADATA)) {
            super.scan(e);
            return this;
        }

        // relies on the fact that structural conflicts can only occur in ordered nodes
        // in unordered nodes, there's no guarantee that a start marker comes before an end marker,
        // for example
        ConflictInfo conflictInfo = (ConflictInfo) e.getMetadata("SPORK_CONFLICT");
        switch (conflictInfo.marker) {
            case LEFT_START:
                writeConflictMarker(START_CONFLICT);
                super.scan(e);
                if (conflictInfo.rightSize == 0) {
                    getPrinterTokenWriter().writeln();
                    writeConflictMarker(MID_CONFLICT);
                    writeConflictMarker(END_CONFLICT);
                }
                break;
            case RIGHT_START:
                if (conflictInfo.leftSize == 0) {
                    writeConflictMarker(START_CONFLICT);
                }
                writeConflictMarker(MID_CONFLICT);
                super.scan(e);
                if (conflictInfo.rightSize == 1) {
                    getPrinterTokenWriter().writeln();
                    writeConflictMarker(END_CONFLICT);
                }
                break;
            case RIGHT_END:
                assert conflictInfo.rightSize > 1;
                super.scan(e);
                getPrinterTokenWriter().writeln();
                writeConflictMarker(END_CONFLICT);
                break;
            default:
                throw new RuntimeException("Internal error, couldn't finish writing conflict");
        }

        return this;
    }

    private void writeConflictMarker(String conflictMarker) {
        TokenWriter printer = getPrinterTokenWriter();
        PrinterHelper helper = printer.getPrinterHelper();
        int tabCount = helper.getTabCount();
        helper.setTabCount(0);

        printer.writeLiteral(conflictMarker);
        printer.writeln();

        helper.setTabCount(tabCount);
    }
}
