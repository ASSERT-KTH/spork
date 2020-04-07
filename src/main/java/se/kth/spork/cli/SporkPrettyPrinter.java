package se.kth.spork.cli;

import se.kth.spork.base3dm.Revision;
import se.kth.spork.spoon.PcsInterpreter;
import se.kth.spork.spoon.StructuralConflict;
import se.kth.spork.util.Pair;
import spoon.compiler.Environment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtComment;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.DefaultTokenWriter;
import spoon.reflect.visitor.PrinterHelper;
import spoon.reflect.visitor.PrintingContext;
import spoon.support.StandardEnvironment;
import spoon.support.sniper.SniperJavaPrettyPrinter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

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

        // This is required to avoid NullPointerExceptions when debugging, as the debugger calls toString from time
        // to time
        printerConflictMaps.push(Optional.empty());

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
        if (e == null) {
            return this;
        } else if (e.getMetadata(PcsInterpreter.SINGLE_REVISION_KEY) != null &&
                (e instanceof CtMethod || e instanceof CtField)) {
            CtElement originalElement = (CtElement) e.getMetadata(PcsInterpreter.ORIGINAL_NODE_KEY);
            String sniped = getOriginalSource(originalElement);
            /**
            env.useTabulations(true); // required for sniper mode
            String sniped = originalElement.toString();
            env.useTabulations(false); // required for SporkPrettyPrinter
             */
            printerHelper.writeSniperContent(sniped, getTabCount(originalElement));
            return this;
        }

        StructuralConflict structuralConflict = (StructuralConflict) e.getMetadata(StructuralConflict.METADATA_KEY);

        if (structuralConflict != null) {
            writeStructuralConflict(structuralConflict);
        } else {
            if (getContext().forceWildcardGenerics()) {
                // Forcing wildcard generics can cause crashes when references can't be resolved, so we don't want
                // to do it. An example of where this sometimes causes crashes is if a nested class is used in an
                // instanceof check.
                try (PrintingContext.Writable _context = getContext().modify()) {
                    _context.forceWildcardGenerics(false);
                    super.scan(e);
                }
            } else {
                super.scan(e);
            }
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

    private static String getOriginalSource(CtElement elem) {
        SourcePosition pos = getSourcePos(elem);
        return getOriginalSource(pos, pos);
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
        byte[] source = start.getCompilationUnit().getOriginalSourceCode().getBytes(Charset.defaultCharset());
        return getOriginalSource(start, end, source);
    }

    /**
     * Get the original source code fragment starting at start and ending at end, including indentation if start is
     * the first element on its source code line.
     *
     * @param start            The source position of the first element.
     * @param end              The source position of the last element.
     * @return The source code fragment starting at start and ending at end, including leading indentation.
     * @throws IOException
     */
    private static String getOriginalSource(SourcePosition start, SourcePosition end, byte[] source) {
        int startByte = precededByIndentation(source, start) ?
                getLineStartByte(start) : start.getSourceStart();
        int endByte = end.getSourceEnd();

        if (source[startByte] == 32) {
            startByte++;
            endByte++;
        }

        byte[] content = Arrays.copyOfRange(source, startByte, endByte + 1);

        return new String(content, Charset.defaultCharset());
    }

    private static boolean precededByIndentation(byte[] source, SourcePosition pos) {
        int lineStartByte = getLineStartByte(pos);

        for (int i = lineStartByte; i < pos.getSourceStart(); i++) {
            if (source[i] != 32) {
                return false;
            }
        }
        return true;
    }

    private static int getTabCount(CtElement elem) {
        SourcePosition pos = getSourcePos(elem);
        byte[] fileBytes = pos.getCompilationUnit().getOriginalSourceCode().getBytes(Charset.defaultCharset());
        int count = 0;

        int[] lineSepPositions = pos.getCompilationUnit().getLineSeparatorPositions();
        int current = 0;
        while (current < lineSepPositions.length && lineSepPositions[current] > pos.getSourceStart())
            current++;


        while (current + count < pos.getSourceStart()) {
            byte b = fileBytes[current + count];
            if (b != 32) {
                break;
            }
            ++count;
        }
        return count;
    }

    private static int getLineStartByte(SourcePosition pos) {
        if (pos.getLine() == 1)
            return 0;

        int sourceStart = pos.getSourceStart() - 1;

        int[] lineSepPositions = pos.getCompilationUnit().getLineSeparatorPositions();
        int current = 0;
        while (lineSepPositions[current] > sourceStart)
            current++;

        return lineSepPositions[current];
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

        public SporkPrinterHelper writeSniperContent(String s, int tabCount) {
            String[] lines = s.split("\n");
            if (lines.length == 1) {
                return write(s);
            }

            write(lines[0]);

            int initialTabCount = getTabCount();
            setTabCount(tabCount / 4);


            for (int i = 1; i < lines.length; i++) {
                writeln();
                String line = lines[i];
                write(trimIndentation(line, tabCount));
            }
            setTabCount(initialTabCount);
            return this;
        }


        private String trimIndentation(String s, int trimAmount) {
            if (s.length() >= trimAmount && onlyWhitespace(s.substring(0, trimAmount))) {
                return s.substring(trimAmount);
            }
            return s;
        }

        private int determineTabCount(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!Character.isWhitespace(c)) {
                    if (c == '*') { // this is most likely a block comment, which has an extra indentation
                        return i - 1;
                    }
                    return i;
                }
            }
            return 0;
        }

        private boolean onlyWhitespace(String s) {
            for (char c : s.toCharArray()) {
                if (!Character.isWhitespace(c))
                    return false;
            }
            return true;
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
