package se.kth.spork.merge.spoon;

import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import se.kth.spork.merge.Pcs;
import se.kth.spork.merge.Revision;
import se.kth.spork.merge.TStar;
import se.kth.spork.merge.TdmMerge;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtPackage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class Spoon3dmMerge {

    public static CtClass<?> merge(CtClass<?> base, CtClass<?> left, CtClass<?> right) {
        ITree baseGumtree = new SpoonGumTreeBuilder().getTree(base);
        ITree leftGumtree = new SpoonGumTreeBuilder().getTree(left);
        ITree rightGumtree = new SpoonGumTreeBuilder().getTree(right);

        Matcher baseLeftGumtreeMatch = matchTrees(baseGumtree, leftGumtree);
        Matcher baseRightGumtreeMatch = matchTrees(baseGumtree, leftGumtree);
        // TODO augment with left-right matchings
        //Matcher leftRightMatch = matchTrees(leftGumtree, baseGumtree);

        SpoonMapping baseLeft = SpoonMapping.fromGumTreeMapping(baseLeftGumtreeMatch.getMappings());
        SpoonMapping baseRight = SpoonMapping.fromGumTreeMapping(baseRightGumtreeMatch.getMappings());

        Map<CtWrapper, CtWrapper> classRepMap = initializeClassRepresentatives(base);
        mapToClassRepresentatives(left, baseLeft, classRepMap, Revision.LEFT);
        mapToClassRepresentatives(right, baseRight, classRepMap, Revision.RIGHT);

        Set<Pcs<CtWrapper>> t0 = SpoonPcs.fromSpoon(base.getParent(), Revision.BASE);
        Set<Pcs<CtWrapper>> t1 = SpoonPcs.fromSpoon(left.getParent(), Revision.LEFT);
        Set<Pcs<CtWrapper>> t2 = SpoonPcs.fromSpoon(right.getParent(), Revision.RIGHT);

        TStar<CtWrapper> delta = new TStar<>(classRepMap, new GetContent(), t0, t1, t2);
        TStar<CtWrapper> t0Star = new TStar<>(classRepMap, new GetContent(), t0);

        TdmMerge.resolveRawMerge(t0Star, delta);

        return SpoonPcs.fromPcs(delta.getStar());
    }

    private static class GetContent implements Function<CtWrapper, Object> {

        @Override
        public Object apply(CtWrapper wrapper) {
            if (wrapper == null)
                return null;

            CtElement elem = wrapper.getElement();
            if (elem instanceof CtModule || elem instanceof CtPackage)
                return null;

            return elem.getShortRepresentation();
        }
    }

    private static Map<CtWrapper, CtWrapper> initializeClassRepresentatives(CtElement base) {
        Map<CtWrapper, CtWrapper> classRepMap = new HashMap<>();
        Iterator<CtElement> descIt = base.descendantIterator();
        while (descIt.hasNext()) {
            CtElement tree = descIt.next();
            CtWrapper wrapped = WrapperFactory.wrap(tree);
            classRepMap.put(wrapped, wrapped);
        }
        return classRepMap;
    }

    private static void mapToClassRepresentatives(CtElement tree, SpoonMapping mappings, Map<CtWrapper, CtWrapper> classRepMap, Revision rev) {
        Iterator<CtElement> descIt = tree.descendantIterator();
        while (descIt.hasNext()) {
            CtElement t = descIt.next();
            t.putMetadata(TdmMerge.REV, rev);
            CtWrapper wrapped = WrapperFactory.wrap(t);

            if (mappings.hasDst(wrapped)) {
                classRepMap.put(wrapped, mappings.getSrc(wrapped));
            } else {
                classRepMap.put(wrapped, wrapped);
            }
        }
    }

    private static Matcher matchTrees(ITree src, ITree dst) {
        Matcher matcher = Matchers.getInstance().getMatcher(src, dst);
        matcher.match();
        return matcher;
    }
}
