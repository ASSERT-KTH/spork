package se.kth.spork.spoon.printer;

import java.util.*;
import kotlin.Pair;
import kotlin.Triple;
import se.kth.spork.exception.ConflictException;
import se.kth.spork.spoon.conflict.ContentConflict;
import se.kth.spork.spoon.conflict.ModifierHandler;
import se.kth.spork.spoon.wrappers.RoledValue;
import se.kth.spork.util.LineBasedMergeKt;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;

/**
 * A pre-processor that must run before pretty-printing a merged tree. It does things like embedding
 * conflict values into literals and unsetting the source position of comments (so they get
 * printed).
 *
 * @author Simon Larsén
 */
public class PrinterPreprocessor extends CtScanner {
    public static final String RAW_COMMENT_CONFLICT_KEY = "spork_comment_conflict";
    public static final String LOCAL_CONFLICT_MAP_KEY = "spork_local_conflict_map";
    public static final String GLOBAL_CONFLICT_MAP_KEY = "spork_global_conflict_map";
    public static final String CONTENT_CONFLICT_PREFIX = "__SPORK_CONFLICT_";

    private final List<String> importStatements;
    private final String activePackage;

    private final Map<String, Set<CtPackageReference>> refToPack;
    private final boolean diff3;

    private int currentConflictId;

    // A mapping with content_conflict_id -> (left_side, right_side) mappings that are valid
    // in the entire source tree
    // TODO improve the pretty-printer such that this hack is redundant
    private final Map<String, Triple<String, String, String>> globalContentConflicts;

    public PrinterPreprocessor(List<String> importStatements, String activePackage, boolean diff3) {
        this.importStatements = importStatements;
        this.activePackage = activePackage;
        refToPack = new HashMap<>();
        currentConflictId = 0;
        globalContentConflicts = new HashMap<>();
        this.diff3 = diff3;
    }

    @Override
    public void scan(CtElement element) {
        if (element == null) return;

        element.putMetadata(
                GLOBAL_CONFLICT_MAP_KEY, Collections.unmodifiableMap(globalContentConflicts));

        // FIXME Temporary fix for bug in Spoon. See method javadoc. Remove once fixed in Spoon.
        handleIncorrectExplicitPackages(element);

        @SuppressWarnings("unchecked")
        List<ContentConflict> conflicts =
                (List<ContentConflict>) element.getMetadata(ContentConflict.METADATA_KEY);

        if (conflicts != null) {
            conflicts.forEach(conf -> processConflict(conf, element));
        }

        super.scan(element);
    }

    /**
     * There's a bug in Spoon that causes packages that should be implicit to be explicit. There's
     * another bug that sometimes attaches the wrong package to references that don't have an
     * explicit package in the source code. This method attempts to mark all such occasions of
     * packages implicit, so they are not reflected in the final output.
     *
     * <p>See https://github.com/kth/spork/issues/94
     *
     * <p>For each package reference attached to a type reference, mark as implicit if:
     *
     * <p>1. The package reference refers to the package of the current compilation unit. 2. The
     * type has been explicitly imported. 3. All types in the package have been imported with a *
     *
     * @param element An element.
     */
    private void handleIncorrectExplicitPackages(CtElement element) {
        if (element instanceof CtPackageReference) {
            CtPackageReference pkgRef = (CtPackageReference) element;
            String pkgName = pkgRef.getQualifiedName();
            CtElement parent = element.getParent();
            if (pkgName.equals(activePackage) || pkgRef.getSimpleName().isEmpty()) {
                element.setImplicit(true);
            } else if (parent instanceof CtTypeReference) {
                String parentQualName = ((CtTypeReference<?>) parent).getQualifiedName();

                for (String imp : importStatements) {
                    if (imp.equals(parentQualName)
                            || imp.endsWith("*")
                                    && pkgName.equals(imp.substring(0, imp.length() - 2))) {
                        element.setImplicit(true);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Process a conflict, and potentially mutate the element with the conflict. For example, values
     * represented as strings may have the conflict embedded directly into the literal.
     *
     * @param conflict A content conflict.
     * @param element The element associated with the conflict.
     */
    @SuppressWarnings("unchecked")
    private void processConflict(ContentConflict conflict, CtElement element) {
        Object leftVal = conflict.getLeft().getValue();
        Object rightVal = conflict.getRight().getValue();
        Object baseVal = conflict.getBase() == null ? "" : conflict.getBase().getValue();

        // The local printer map, unlike the global printer map, is only valid in the scope of the
        // current CtElement. It contains conflicts for anything that can't be replaced with a
        // conflict id,
        // such as operators and modifiers (as these are represented by enums)
        // TODO improve the pretty-printer such that this hack is redundant
        Map<String, Triple<String, String, String>> localPrinterMap = new HashMap<>();

        switch (conflict.getRole()) {
            case NAME:
            case VALUE:
                // these need to go into the global conflicts map, as the nodes in question aren't
                // always scanned separately by the printer (often it just calls `getSimpleName`)
                String conflictKey = CONTENT_CONFLICT_PREFIX + currentConflictId++;
                globalContentConflicts.put(
                        conflictKey,
                        new Triple<>(leftVal.toString(), rightVal.toString(), baseVal.toString()));
                element.setValueByRole(conflict.getRole(), conflictKey);
                break;
            case COMMENT_CONTENT:
                String rawLeft =
                        (String) conflict.getLeft().getMetadata(RoledValue.Key.RAW_CONTENT);
                String rawRight =
                        (String) conflict.getRight().getMetadata(RoledValue.Key.RAW_CONTENT);
                String rawBase =
                        conflict.getBase() != null
                                ? (String)
                                        conflict.getBase().getMetadata(RoledValue.Key.RAW_CONTENT)
                                : "";

                Pair<String, Integer> rawConflict =
                        LineBasedMergeKt.lineBasedMerge(rawBase, rawLeft, rawRight, diff3);
                assert rawConflict.getSecond() > 0
                        : "Comments without conflict should already have been merged";

                element.putMetadata(RAW_COMMENT_CONFLICT_KEY, rawConflict.getFirst());
                break;
            case IS_UPPER:
                if (leftVal.equals(true)) {
                    localPrinterMap.put("extends", new Triple<>("extends", "super", ""));
                } else {
                    localPrinterMap.put("super", new Triple<>("super", "extends", ""));
                }
                break;
            case MODIFIER:
                Collection<ModifierKind> leftMods = (Collection<ModifierKind>) leftVal;
                Collection<ModifierKind> rightMods = (Collection<ModifierKind>) rightVal;
                Collection<ModifierKind> baseMods = (Collection<ModifierKind>) baseVal;
                Set<ModifierKind> leftVisibilities =
                        ModifierHandler.Companion.categorizeModifiers(leftMods).getFirst();
                Set<ModifierKind> rightVisibilities =
                        ModifierHandler.Companion.categorizeModifiers(rightMods).getFirst();
                Set<ModifierKind> baseVisibilities =
                        baseVal == null
                                ? Collections.emptySet()
                                : ModifierHandler.Companion.categorizeModifiers(baseMods)
                                        .getFirst();

                String baseVisStr =
                        baseVisibilities.isEmpty()
                                ? ""
                                : baseVisibilities.iterator().next().toString();
                if (leftVisibilities.isEmpty()) {
                    // use the right-hand visibility in actual tree to force something to be printed
                    Collection<ModifierKind> mods = element.getValueByRole(CtRole.MODIFIER);
                    ModifierKind rightVis = rightVisibilities.iterator().next();
                    mods.add(rightVis);
                    element.setValueByRole(CtRole.MODIFIER, mods);
                    localPrinterMap.put(
                            rightVis.toString(), new Triple<>("", rightVis.toString(), baseVisStr));
                } else {
                    String leftVisStr = leftVisibilities.iterator().next().toString();
                    String rightVisStr =
                            rightVisibilities.isEmpty()
                                    ? ""
                                    : rightVisibilities.iterator().next().toString();
                    localPrinterMap.put(
                            leftVisStr, new Triple<>(leftVisStr, rightVisStr, baseVisStr));
                }
                break;
            case OPERATOR_KIND:
                assert leftVal.getClass() == rightVal.getClass();

                String leftStr = OperatorHelper.getOperatorText(leftVal);
                String rightStr = OperatorHelper.getOperatorText(rightVal);
                String baseStr = baseVal == null ? "" : OperatorHelper.getOperatorText(baseVal);

                if (element instanceof CtOperatorAssignment) {
                    leftStr += "=";
                    rightStr += "=";
                    baseStr += "=";
                }
                localPrinterMap.put(leftStr, new Triple<>(leftStr, rightStr, baseStr));
                break;
            default:
                throw new ConflictException("Unhandled conflict: " + leftVal + ", " + rightVal);
        }

        if (!localPrinterMap.isEmpty()) {
            element.putMetadata(LOCAL_CONFLICT_MAP_KEY, localPrinterMap);
        }
    }
}
