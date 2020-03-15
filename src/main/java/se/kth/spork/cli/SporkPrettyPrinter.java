package se.kth.spork.cli;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.spoon.StructuralConflict;
import se.kth.spork.util.Pair;
import spoon.compiler.Environment;
import spoon.reflect.code.CtComment;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.DefaultTokenWriter;
import spoon.reflect.visitor.PrinterHelper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.*;

public final class SporkPrettyPrinter extends DefaultJavaPrettyPrinter {
    public static final String START_CONFLICT = "<<<<<<< LEFT";
    public static final String MID_CONFLICT = "=======";
    public static final String END_CONFLICT = ">>>>>>> RIGHT";

    private final SporkPrinterHelper printerHelper;
    private final String lineSeparator = getLineSeparator();

    private Deque<Optional<Map<String, Pair<Revision, String>>>> printerConflictMaps;

    public SporkPrettyPrinter(Environment env) {
        super(env);
        printerHelper = new SporkPrinterHelper(env);
        printerConflictMaps = new ArrayDeque<>();
        setPrinterTokenWriter(new DefaultTokenWriter(printerHelper));

        // this line is SUPER important because without it implicit elements will be printed. For example,
        // instead of just String, it will print java.lang.String. Which isn't great.
        setIgnoreImplicit(false);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void enter(CtElement e) {
        printerConflictMaps.push(Optional.ofNullable(
                (Map<String, Pair<Revision, String>>) e.getMetadata(PrinterPreprocessor.CONFLICT_MAP_KEY)));
        super.enter(e);
    }

    @Override
    protected void exit(CtElement e) {
        printerConflictMaps.pop();
        super.exit(e);
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
        Object rawConflict = comment.getMetadata(PrinterPreprocessor.RAW_COMMENT_CONFLICT_KEY);
        if (rawConflict != null) {
            printerHelper.writeRawConflict(rawConflict.toString());
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
        printerHelper.writeConflict(leftSource, rightSource);
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
     * @param start            The source position of the first element.
     * @param end              The source position of the last element.
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

    private class SporkPrinterHelper extends PrinterHelper {
        private String lastWritten;

        public SporkPrinterHelper(Environment env) {
            super(env);
            lastWritten = "";
        }

        @Override
        public SporkPrinterHelper write(String s) {
            Optional<Map<String, Pair<Revision, String>>> conflictMap = printerConflictMaps.peek();
            String trimmed = s.trim();
            lastWritten = s;
            if (trimmed.startsWith(START_CONFLICT) && trimmed.endsWith(END_CONFLICT)) {
                // this is an embedded conflict value, which is necessary for names sometimes as the
                // DefaultJavaPrettyPrinter does not always visit the node (e.g. when printing the name of a variable
                // in a variable access), and just uses getSimpleName(). This causes the conflict map for that node
                // to not be present when its name is written.
                //
                // All we need to do here is the decrease tabs and enter some appropriate whitespace
                writelnIfNotPresent().writeAtLeftMargin(s).writeln();
            } else if (conflictMap.isPresent() && conflictMap.get().containsKey(s)) {
                Pair<Revision, String> conflict = conflictMap.get().get(s);
                String left = conflict.first == Revision.RIGHT ? s : conflict.second;
                String right = conflict.first == Revision.RIGHT ? conflict.second : s;
                writeConflict(left, right);
            } else {
                super.write(s);
            }

            return this;
        }

        public SporkPrinterHelper writeConflict(String left, String right) {
            writelnIfNotPresent().writeAtLeftMargin(START_CONFLICT).writeln()
                    .writeAtLeftMargin(left).writelnIfNotPresent()
                    .writeAtLeftMargin(MID_CONFLICT).writeln()
                    .writeAtLeftMargin(right).writelnIfNotPresent()
                    .writeAtLeftMargin(END_CONFLICT).writeln();
            return this;
        }

        @Override
        public SporkPrinterHelper writeln() {
            super.writeln();
            return this;
        }

        /**
         * Write a line separator only if the last written string did not end with a line separator.
         */
        private SporkPrinterHelper writelnIfNotPresent() {
            if (lastWritten.endsWith(lineSeparator))
                return this;
            return writeln();
        }

        private SporkPrinterHelper writeAtLeftMargin(String s) {
            if (s.isEmpty())
                return this;

            int tabBefore = getTabCount();
            setTabCount(0);
            super.write(s);
            lastWritten = s;
            setTabCount(tabBefore);
            return this;
        }

        /**
         * Write a raw conflict. Typically, this is used for writing out conflicts in comments.
         */
        public void writeRawConflict(String s) {
            // When getting raw comments from Spoon, they don't include leading whitespace for the first line,
            // so we check if that's needed
            if (getTabCount() > 0 && !s.startsWith("\\s")) {
                // not indented, so we write the first line normally
                String[] lines = s.split(lineSeparator, 2);
                write(lines[0]).writelnIfNotPresent();

                if (lines.length > 1) {
                    // we write the rest of the content with 0 indentation
                    writeAtLeftMargin(lines[1]);
                }
            } else {
                writeAtLeftMargin(s);
            }
        }
    }

}
