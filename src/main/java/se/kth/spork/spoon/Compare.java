package se.kth.spork.spoon;

import spoon.refactoring.Refactoring;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A class for comparing Spoon elements in such a way that the order of unordered elements does not
 * affect the verdict.
 *
 * @author Simon Larsén
 */
public class Compare {

    /**
     * Compare two unnamed modules for equality, without regard to the order of unordered elements. This method only
     * considers the import statements attached to the module by Spork, and the packages (recursively starting with
     * the root packages). It does not consider other module level information, as Spork does not deal with such.
     *
     * @param left An unnamed module.
     * @param right An unnamed module.
     * @return true iff left and right are equal, disregarding the order of unordered elements.
     */
    public static boolean compare(CtModule left, CtModule right) {
        if (!left.isUnnamedModule()) {
            throw new IllegalArgumentException("left is not the unnamed module");
        } else if (!right.isUnnamedModule()) {
            throw new IllegalArgumentException("right is not the unnamed module");
        }

        Object leftImports = left.getMetadata(Parser.IMPORT_STATEMENTS);
        Object rightImports = right.getMetadata(Parser.IMPORT_STATEMENTS);

        return leftImports.equals(rightImports) &&
                compare(left.getRootPackage().clone(), right.getRootPackage().clone());
    }

    /**
     * Compare the left package with the right package, without regard to the order of unordered type
     * members.
     *
     * @param left  A CtPackage.
     * @param right A CtPackage.`
     * @return true iff the two packages are equal, without regard to the order of unordered elements.
     */
    private static boolean compare(CtPackage left, CtPackage right) {
        if (!left.getQualifiedName().equals(right.getQualifiedName()))
            return false;

        List<CtPackage> sortedLeftSubpackages = sortedBySimpleName(left.getPackages());
        List<CtPackage> sortedRightSubpackages = sortedBySimpleName(right.getPackages());

        boolean namesMatch = left.getQualifiedName().equals(right.getQualifiedName());
        boolean typesMatch = compareTypeLists(left.getTypes(), right.getTypes());
        boolean subPackSizesMatch = sortedLeftSubpackages.size() == sortedRightSubpackages.size();
        boolean subPacksMatch = IntStream.range(0, sortedLeftSubpackages.size())
                        .mapToObj(i ->
                                compare(
                                        sortedLeftSubpackages.get(i),
                                        sortedRightSubpackages.get(i))).allMatch(b -> b);
        return namesMatch && typesMatch && subPackSizesMatch && subPacksMatch;
    }

    private static boolean compareTypeLists(Set<CtType<?>> leftTypes, Set<CtType<?>> rightTypes) {
        if (leftTypes.size() != rightTypes.size())
            return false;

        int size = leftTypes.size();

        List<CtType<?>> sortedLeft = sortedBySimpleName(leftTypes);
        List<CtType<?>> sortedRight = sortedBySimpleName(rightTypes);
        sortedLeft.forEach(Compare::sortTypeMembers);
        sortedRight.forEach(Compare::sortTypeMembers);

        return IntStream.range(0, size)
                .mapToObj(i -> sortedLeft.get(i).equals(sortedRight.get(i)))
                .allMatch(b -> b);
    }

    /**
     * Sort the type members of the provided type according to the following rules:
     * <p>
     * 1. Nested types are recursively sorted, then ordered by simple names and placed last.
     * 2. Methods are ordered by signature and placed above nested classes.
     * 3. Constructors are also ordered by signature and placed above methods.
     * 3. All other type members retain their original order with respect to each other, and are
     * placed at the top.
     * <p>
     *
     * @param type A Spoon type.
     */
    private static void sortTypeMembers(CtType<?> type) {
        List<CtTypeMember> typeMembers = new ArrayList<>(type.getTypeMembers());

        List<CtMethod<?>> methods = new ArrayList<>();
        List<CtType<?>> nestedTypes = new ArrayList<>();
        List<CtConstructor<?>> constructors = new ArrayList<>();
        List<CtTypeMember> other = new ArrayList<>();

        for (CtTypeMember mem : typeMembers) {
            mem.delete();
            mem.setValueByRole(CtRole.POSITION, SourcePosition.NOPOSITION);

            if (mem instanceof CtMethod) {
                methods.add((CtMethod<?>) mem);
            } else if (mem instanceof CtConstructor) {
                constructors.add((CtConstructor<?>) mem);
            } else if (mem instanceof CtType) {
                CtType<?> nestedType = (CtType<?>) mem;
                sortTypeMembers(nestedType);
                nestedTypes.add(nestedType);
            } else {
                other.add(mem);
            }
        }

        sortBySimpleName(nestedTypes);
        methods.sort(Comparator.comparing(CtMethod::getSignature));
        constructors.sort(Comparator.comparing(CtConstructor::getSignature));

        List<CtTypeMember> orderedMembers = new ArrayList<>();
        orderedMembers.addAll(other);
        orderedMembers.addAll(constructors);
        orderedMembers.addAll(methods);
        orderedMembers.addAll(nestedTypes);

        for (int i = 0; i < orderedMembers.size(); i++) {
            type.addTypeMemberAt(i, orderedMembers.get(i));
        }
    }

    private static void sortBySimpleName(List<CtType<?>> types) {
        types.sort(Comparator.comparing(CtType::getSimpleName));
    }

    private static <T extends CtNamedElement> List<T> sortedBySimpleName(Collection<T> elems) {
        return elems.stream()
                .sorted(Comparator.comparing(CtNamedElement::getSimpleName))
                .collect(Collectors.toList());
    }
}
