package se.kth.spork.cli;

import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

/**
 * A class for extracting source code and related information from Spoon nodes.
 *
 * @author Simon Larsén
 */
public class SourceExtractor {

    /**
     * Get the original source fragment corresponding to the nodes provided, or an empty string if the list is
     * empty. Note that the nodes must be adjacent in the source file, and in the same order as in the source.
     *
     * @param nodes A possibly empty list of adjacent nodes.
     * @return The original source code fragment, including any leading indentation on the first line.
     */
    static String getOriginalSource(List<CtElement> nodes) {
        if (nodes.isEmpty())
            return "";

        SourcePosition firstElemPos = getSourcePos(nodes.get(0));
        SourcePosition lastElemPos = getSourcePos(nodes.get(nodes.size() - 1));
        return getOriginalSource(firstElemPos, lastElemPos);
    }

    /**
     * Get the original source code fragment associated with this element, including any leading indentation.
     *
     * @param elem A Spoon element.
     * @return The original source code associated with the elemen.
     */
    static String getOriginalSource(CtElement elem) {
        SourcePosition pos = getSourcePos(elem);
        return getOriginalSource(pos, pos);
    }

    /**
     * Return the indentation count for this element. This is a bit hit-and-miss, but it usually works. It finds
     * the line that the element starts on, and counts the amount of indentation characters until the first character
     * on the line.
     *
     * @param elem A Spoon element.
     * @return The amount of indentation characters preceding the first non-indentation character on the line this
     *      element is defined on.
     */
    static int getIndentation(CtElement elem) {
        SourcePosition pos = getSourcePos(elem);
        byte[] fileBytes = pos.getCompilationUnit().getOriginalSourceCode().getBytes(Charset.defaultCharset());
        int count = 0;

        int[] lineSepPositions = pos.getCompilationUnit().getLineSeparatorPositions();
        int current = 0;
        while (current < lineSepPositions.length && lineSepPositions[current] > pos.getSourceStart())
            current++;


        while (current + count < pos.getSourceStart()) {
            byte b = fileBytes[current + count];
            if (!isIndentation(b)) {
                break;
            }
            ++count;
        }
        return count;
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
     * @param start The source position of the first element.
     * @param end   The source position of the last element.
     * @return The source code fragment starting at start and ending at end, including leading indentation.
     * @throws IOException
     */
    private static String getOriginalSource(SourcePosition start, SourcePosition end, byte[] source) {
        int startByte = precededByIndentation(source, start) ?
                getLineStartByte(start) : start.getSourceStart();
        int endByte = end.getSourceEnd();

        if (isIndentation(source[startByte])) {
            startByte++;
            endByte++;
        }

        byte[] content = Arrays.copyOfRange(source, startByte, endByte + 1);

        return new String(content, Charset.defaultCharset());
    }

    private static boolean precededByIndentation(byte[] source, SourcePosition pos) {
        int lineStartByte = getLineStartByte(pos);

        for (int i = lineStartByte; i < pos.getSourceStart(); i++) {
            if (!isIndentation(source[i])) {
                return false;
            }
        }
        return true;
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

    private static boolean isIndentation(byte b) {
        return b == ' ' || b == '\t';
    }
}
