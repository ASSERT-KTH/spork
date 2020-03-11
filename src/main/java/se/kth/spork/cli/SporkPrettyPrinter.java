package se.kth.spork.cli;

import se.kth.spork.spoon.StructuralConflict;
import spoon.compiler.Environment;
import spoon.reflect.code.CtComment;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.List;

public class SporkPrettyPrinter extends DefaultJavaPrettyPrinter {
    public static final String START_CONFLICT = "<<<<<<< LEFT";
    public static final String MID_CONFLICT = "=======";
    public static final String END_CONFLICT = ">>>>>>> RIGHT";

    public SporkPrettyPrinter(Environment env) {
        super(env);

        // this line is SUPER important because without it implicit elements will be printed. For example,
        // instead of just String, it will print java.lang.String. Which isn't great.
        setIgnoreImplicit(false);
    }

    @Override
    public SporkPrettyPrinter scan(CtElement e) {
        if (e == null)
            return this;

        StructuralConflict structuralConflict = (StructuralConflict) e.getMetadata(StructuralConflict.METADATA_KEY);

        if (structuralConflict != null) {
            writeStructuralConflict(structuralConflict);
        } else {
            super.scan(e);
        }

        return this;
    }

    @Override
    public void visitCtComment(CtComment comment) {
        @SuppressWarnings("unchecked")
        Object rawConflict = comment.getMetadata(PrinterPreprocessor.RAW_COMMENT_CONFLICT_KEY);
        if (rawConflict != null) {
            writeAtLeftMargin(rawConflict.toString());
        } else {
            super.visitCtComment(comment);
        }
    }

    /**
     * Write both pats of a structural conflict.
     */
    private void writeStructuralConflict(StructuralConflict structuralConflict) {
        String leftSource = getOriginalSource(structuralConflict.left);
        String rightSource = getOriginalSource(structuralConflict.right);
        writeConflict(leftSource, rightSource);
    }

    private void writeConflict(String left, String right) {
        PrinterHelper helper = getPrinterTokenWriter().getPrinterHelper();
        int tabBefore = helper.getTabCount();
        helper.setTabCount(0);

        helper.writeln();
        writeConflictMarker(START_CONFLICT);
        writelnNonEmpty(left);
        writeConflictMarker(MID_CONFLICT);
        writelnNonEmpty(right);
        writeConflictMarker(END_CONFLICT);

        helper.setTabCount(tabBefore);
    }

    private PrinterHelper writeAtLeftMargin(String s) {
        PrinterHelper helper = getPrinterTokenWriter().getPrinterHelper();
        int tabBefore = helper.getTabCount();
        helper.setTabCount(0);
        helper.write(s);
        return helper.setTabCount(tabBefore);
    }

    private void writeConflictMarker(String conflictMarker) {
        TokenWriter printer = getPrinterTokenWriter();
        printer.writeLiteral(conflictMarker);
        printer.writeln();
    }

    /**
     * Write the provided string plus a line ending only if the string is non-empty.
     */
    private void writelnNonEmpty(String s) {
        if (s.isEmpty())
            return;

        PrinterHelper helper = getPrinterTokenWriter().getPrinterHelper();

        helper.write(s);
        helper.writeln();
    }

    /**
     * Get the original source fragment corresponding to the nodes provided, or an empty string if the list is
     * empty. Note that the nodes must be adjacent in the source file, and in the same order as in the source.
     *
     * @param nodes A possibly empty list of adjacent nodes.
     * @return The original source code fragment, including any leading indentation on the first line.
     */
    private static String getOriginalSource(List<CtElement> nodes) {
        if (nodes.isEmpty())
            return "";

        SourcePosition firstElemPos = getSourcePos(nodes.get(0));
        SourcePosition lastElemPos = getSourcePos(nodes.get(nodes.size() - 1));
        return getOriginalSource(firstElemPos, lastElemPos);
    }

    /**
     * Get the source file position from a CtElement, taking care that Spork sometimes stores position information as
     * metadata to circumvent the pretty-printers reliance on positional information (e.g. when printing comments).
     */
    private static SourcePosition getSourcePos(CtElement elem) {
        SourcePosition pos = (SourcePosition) elem.getMetadata(PrinterPreprocessor.POSITION_KEY);
        if (pos == null) {
            pos = elem.getPosition();
        }
        assert pos != null && pos != SourcePosition.NOPOSITION;

        return pos;
    }

    private static String getOriginalSource(SourcePosition start, SourcePosition end) {
        try (RandomAccessFile file = new RandomAccessFile(start.getFile(), "r")) {
            return getOriginalSource(start, end, file);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("failed to read original source fragments");
        }
    }

    /**
     * Get the original source code fragment starting at start and ending at end, including indentation if start is
     * the first element on its source code line.
     *
     * @param start The source position of the first element.
     * @param end The source position of the last element.
     * @param randomAccessFile A random access file pointing to the common source of the start and end elements.
     * @return The source code fragment starting at start and ending at end, including leading indentation.
     * @throws IOException
     */
    private static String getOriginalSource(SourcePosition start, SourcePosition end, RandomAccessFile randomAccessFile) throws IOException {
        int startByte = precededByIndentation(randomAccessFile, start) ?
                getLineStartByte(start) : start.getSourceStart();
        int endByte = end.getSourceEnd();

        byte[] content = new byte[endByte - startByte + 1];

        randomAccessFile.seek(startByte);
        randomAccessFile.read(content);

        return new String(content, Charset.defaultCharset());
    }

    private static boolean precededByIndentation(RandomAccessFile file, SourcePosition pos) throws IOException {
        int lineStartByte = getLineStartByte(pos);
        byte[] before = new byte[pos.getSourceStart() - lineStartByte];

        file.seek(lineStartByte);
        file.read(before);

        String s = new String(before, Charset.defaultCharset());
        return s.matches("\\s+");
    }

    private static int getLineStartByte(SourcePosition pos) {
        if (pos.getLine() == 1)
            return 0;

        // the index is offset by 2 because:
        // 1. zero-indexing means -1
        // 2. line separator indexing means that the 0th line separator corresponds to the _end_ of the first line
        int prevLineEnd = pos.getLine() - 2;
        return pos.getCompilationUnit().getLineSeparatorPositions()[prevLineEnd] + 1; // +1 to get the next line start
    }
}
