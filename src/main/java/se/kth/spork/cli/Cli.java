package se.kth.spork.cli;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.merge.GumTreeBuilder;
import se.kth.spork.merge.TStar;
import se.kth.spork.merge.TdmMerge;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Command line interface for Spork.
 *
 * @author Simon Larsén
 */
public class Cli {
    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            usage();
            System.exit(1);
        }

        String left = readFile(args[0]);
        String base = readFile(args[1]);
        String right = readFile(args[2]);
        String expected = args.length == 4 ? readFile(args[3]) : null;

        CtClass<?> baseTree = Launcher.parseClass(base);
        CtClass<?> leftTree = Launcher.parseClass(left);
        CtClass<?> rightTree = Launcher.parseClass(right);

        ITree mergedTree = TdmMerge.mergeToTree(baseTree, leftTree, rightTree);

        if (expected != null) {
            ITree expectedTree = toGumTree(expected);
            System.out.println("Merge isomorphic to expected tree: " + mergedTree.isIsomorphicTo(expectedTree));

            System.out.println("EXPECTED TREE");
            expectedTree.preOrder().forEach(t -> System.out.println(t.toShortString()));


            System.out.println("ACTUAL TREE");
            mergedTree.preOrder().forEach(t -> System.out.println(t.toShortString()));
        }
    }

    private static String readFile(String s) throws IOException {
        Path path = Paths.get(s);
        if (!path.toFile().isFile())
            throw new IllegalArgumentException("no such file: " + path);
        return Files.lines(path).collect(Collectors.joining("\n"));
    }

    private static ITree toGumTree(String clazz) {
        CtClass<?> spoonTree = Launcher.parseClass(clazz);
        SpoonGumTreeBuilder builder = new SpoonGumTreeBuilder();
        return builder.getTree(spoonTree);
    }

    private static Matcher matchTrees(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher(src, dst);
        matcher.match();
        return matcher;
    }

    private static void usage() {
        System.out.println("usage: spork <left> <base> <right> [expected]");
    }
}
