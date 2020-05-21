package se.kth.spork.spoon.printer;

import se.kth.spork.spoon.pcsinterpreter.SpoonTreeBuilder;
import se.kth.spork.util.Pair;
import spoon.reflect.CtModel;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtTypeMember;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A class for extracting source code and related information from Spoon nodes.
 *
 * @author Simon Larsén
 */
public class SourceExtractor {
    public static final int DEFAULT_INDENTATION_SIZE = 4;
    public static final boolean DEFAULT_IS_TABS = false;

    /**
     * Get the original source fragment corresponding to the nodes provided, or an empty string if the list is
     * empty. Note that the nodes must be adjacent in the source file, and in the same order as in the source.
     *
     * @param nodes A possibly empty list of adjacent nodes.
     * @return The original source code fragment, including any leading indentation on the first line.
     */
    public static String getOriginalSource(List<CtElement> nodes) {
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
    public static String getOriginalSource(CtElement elem) {
        SourcePosition pos = getSourcePos(elem);
        return getOriginalSource(pos, pos);
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
    private static String getOriginalSource(SourcePosition start, SourcePosition end) {
        CompilationUnit cu = start.getCompilationUnit();
        String source = cu.getOriginalSourceCode();
        int startChar = precededByIndentation(source, start) ?
                getLineStartIdx(start) : start.getSourceStart();
        int endChar = end.getSourceEnd();
        return source.substring(startChar, endChar + 1);
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
    public static int getIndentation(CtElement elem) {
        SourcePosition pos = getSourcePos(elem);
        int current = getLineStartIdx(pos);
        return getIndentation(pos, current);
    }

    /**
     * Internal method for getting the indentation of a source position, assuming that lineStartIdx is a correct index
     * for the line the element starts on..
     */
    private static int getIndentation(SourcePosition pos, int lineStartIdx) {
        String source = pos.getCompilationUnit().getOriginalSourceCode();
        int count = 0;
        while (lineStartIdx + count < pos.getSourceStart()) {
            char c = source.charAt(lineStartIdx + count);
            if (!isIndentation(c)) {
                break;
            }
            ++count;
        }
        return count;
    }

    /**
     * Get the indentation size, and the type of the indentation (tabs or spaces), for the provided element.
     *
     * @param elem An element.
     * @return A pair on the form (indentationSize, isTabs).
     */
    public static Pair<Integer, Boolean> getIndentationInfo(CtElement elem) {
        SourcePosition pos = elem.getPosition();
        int lineStartIdx = getLineStartIdx(pos);
        int indentationSize = getIndentation(pos, lineStartIdx);
        boolean isTabs = pos.getCompilationUnit().getOriginalSourceCode().charAt(lineStartIdx) == '\t';
        return Pair.of(indentationSize, isTabs);
    }

    /**
     * Guess the indentation size and if indentation is using tabs or spaces in the provided module. If no educated
     * guess can be made, falls back on the defaults of {@link SourceExtractor#DEFAULT_INDENTATION_SIZE} and
     * {@link SourceExtractor#DEFAULT_IS_TABS}.
     *
     * The indentation size will only ever be guessed as 1, 2 or 4.
     *
     * @param model A Spoon model.
     * @return A pair (indentationSize, isTabs).
     */
    public static Pair<Integer, Boolean> guessIndentation(CtModel model) {
        List<CtTypeMember> topLevelMembers = model.getAllTypes().stream()
                .filter(e -> e.getPosition().getFile() != null)
                .filter(e -> getIndentation(e) == 0)
                .flatMap(e -> e.getTypeMembers().stream())
                .filter(member -> !member.isImplicit())
                .limit(20)
                .collect(Collectors.toList());
        if (topLevelMembers.isEmpty()) {
            return Pair.of(DEFAULT_INDENTATION_SIZE, DEFAULT_IS_TABS);
        }

        List<Pair<Integer, Boolean>> memberIndentations = topLevelMembers.stream()
                .map(SourceExtractor::getIndentationInfo).collect(Collectors.toList());
        long numStartsWithTab = memberIndentations.stream().filter(Pair::getSecond).count();
        // guestimate if tabs are used as indentations if more than half of the members are tab indented
        boolean isTabs = numStartsWithTab > (double) memberIndentations.size() / 2;
        double indentationMean = memberIndentations.stream()
                .mapToDouble(Pair::getFirst).sum() / memberIndentations.size();

        double diff1 = Math.abs(1 - indentationMean);
        double diff2 = Math.abs(2 - indentationMean);
        double diff4 = Math.abs(4 - indentationMean);

        int indentationSize;
        if (diff1 > diff2) {
            indentationSize = diff2 > diff4 ? 4 : 2;
        } else {
            indentationSize = 1;
        }

        return Pair.of(indentationSize, isTabs);
    }

    /**
     * @param elem A Spoon element.
     * @return true if the element has a valid source position.
     */
    public static boolean hasSourcePos(CtElement elem) {
        return getSourcePos(elem).isValidPosition();
    }

    /**
     * Get the source file position from a CtElement, taking care that Spork sometimes stores position information as
     * metadata to circumvent the pretty-printers reliance on positional information (e.g. when printing comments).
     */
    private static SourcePosition getSourcePos(CtElement elem) {
        SourcePosition pos = (SourcePosition) elem.getMetadata(SpoonTreeBuilder.POSITION_KEY);
        if (pos == null) {
            pos = elem.getPosition();
        }
        return pos;
    }

    private static boolean precededByIndentation(String source, SourcePosition pos) {
        int lineStartIdx = getLineStartIdx(pos);

        for (int i = lineStartIdx; i < pos.getSourceStart(); i++) {
            if (!isIndentation(source.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int getLineStartIdx(SourcePosition pos) {
        if (pos.getLine() == 1)
            return 0;

        int cur = pos.getSourceStart();
        String source = pos.getCompilationUnit().getOriginalSourceCode();
        while (source.charAt(cur) != '\n')
            cur--;

        return cur + 1;
    }

    private static boolean isIndentation(char c) {
        return c == ' ' || c == '\t';
    }
}
