package se.kth.spork.merge;

import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import spoon.Launcher;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtTypeReference;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class for transforming a GumTree tree to a Spoon tree. Every node in the GumTree tree must be mapped to a Spoon node.
 *
 * @author Simon Larsén
 */
public class SpoonBuilder {
    private Launcher launcher;
    private Factory factory;

    public SpoonBuilder() {
        launcher = new Launcher();
        launcher.getEnvironment().setAutoImports(true);
        factory = launcher.getFactory();
    }

    /**
     * Build a Spoon CtClass tree from a GumTree tree. All nodes in the GumTree are expected to map to a Spoon node.
     * In other words, each node must contain SpoonGumTreeBuilder.SPOON_OBJECT metadata.
     *
     * @param gumTree A merged GumTree tree.
     * @return An equivalent Spoon tree.
     */
    public CtClass<?> buildSpoonTree(ITree gumTree) {
        ITree root = gumTree.getChild(0);
        return buildClass(root);
    }

    private CtClass<?> buildClass(ITree gtClass) {
        ITree modifiers = gtClass.getChild(0);
        CtClass<?> cls = factory.createClass(gtClass.getLabel());

        ModifierKind visibility = parseModifier(modifiers.getChild(0));
        cls.setVisibility(visibility);

        for (int i = 1; i < gtClass.getChildren().size(); i++) {
            ITree child = gtClass.getChild(i);
            parseAndAddTopLevelNode(child, cls);
        }

        System.out.println(cls);

        return cls;
    }

    private void parseAndAddTopLevelNode(ITree node, CtClass<?> spoonClass) {
        CtElement spoonNode = getSpoonNode(node);
        if (spoonNode instanceof CtMethod<?>) {
            parseAndAddMethod(node, spoonClass);
        } else {
            throw new IllegalStateException("unhandled top level node: " + node.toShortString());
        }
    }

    private void parseAndAddMethod(ITree gtMethod, CtClass<?> spoonClass) {
        String name = gtMethod.getLabel();
        CtTypeReference<?> returnType = parseTypeReference(gtMethod.getChild(0));
        Set<ModifierKind> modifiers = gtMethod.getChild(1).getChildren().stream()
                .map(SpoonBuilder::parseModifier)
                .collect(Collectors.toSet());
        List<CtParameter<?>> params = parseParameters(gtMethod.getChildren());

        CtMethod<?> method = factory.createMethod(spoonClass, modifiers, returnType, name, params,
                /*TODO handle throwables*/new HashSet<>());

        CtBlock<?> body = parseBlock(gtMethod.getChildren());
        method.setBody(body);
    }

    private List<CtParameter<?>> parseParameters(List<ITree> nodes) {
        return nodes.stream()
                .filter(t -> getSpoonNode(t).getRoleInParent() == CtRole.PARAMETER)
                .map(this::parseParameter)
                .collect(Collectors.toList());
    }

    private CtParameter<?> parseParameter(ITree gtParam) {
        String name = gtParam.getLabel();
        CtTypeReference tpe = parseTypeReference(gtParam.getChild(0));
        CtParameter<?> param = factory.createParameter();
        param.setSimpleName(name);
        param.setType(tpe);
        return param;
    }

    private static ModifierKind parseModifier(ITree modifier) {
        return ModifierKind.valueOf(modifier.getLabel().toUpperCase());
    }

    private CtBlock<?> parseBlock(List<ITree> nodes) {
        CtBlock<?> block = factory.createBlock();
        parseStatementList(nodes).forEach(block::addStatement);
        return block;
    }

    private List<CtStatement> parseStatementList(List<ITree> nodes) {
        return parseStatementListByRole(nodes, CtRole.STATEMENT);
    }

    private List<CtStatement> parseStatementListByRole(List<ITree> nodes, CtRole role) {
        List<CtStatement> statements = new ArrayList<>();
        for (ITree node : nodes) {
            CtElement childSpoonNode = getSpoonNode(node);
            CtRole currentRole = childSpoonNode.getRoleInParent();
            if (currentRole == role) {
                CtStatement stat = parseStatement(node);
                statements.add(stat);
            }
        }
        return statements;
    }

    private CtStatement parseStatement(ITree node) {
        CtElement spoonNode = getSpoonNode(node);
        CtStatement stat;

        if (spoonNode instanceof CtReturn) {
            stat = parseReturn(node);
        } else if (spoonNode instanceof CtIf) {
            stat = parseIf(node);
        } else if (spoonNode instanceof CtThrow) {
            stat = parseThrow(node);
        } else if (spoonNode instanceof CtLocalVariable) {
            stat = parseLocalVariable(node);
        } else if (spoonNode instanceof CtFor) {
            stat = parseFor(node);
        } else if (spoonNode instanceof CtUnaryOperator) {
            stat = parseUnaryOperator(node);
        } else if (spoonNode instanceof CtOperatorAssignment) {
            stat = parseOperatorAssignment(node);
        } else {
            throw new IllegalStateException("unhandled statement: " + spoonNode.getShortRepresentation());
        }

        // each statement can have one comment
        for (ITree child : node.getChildren()) {
            CtElement childSpoonNode = getSpoonNode(child);
            CtRole role = childSpoonNode.getRoleInParent();
            if (role == CtRole.COMMENT) {
                stat.addComment((CtComment) childSpoonNode.clone());
            }
        }

        return stat;
    }

    private CtStatement parseOperatorAssignment(ITree node) {
        assert node.getChildren().size() == 2;
        CtOperatorAssignment orig = (CtOperatorAssignment) getSpoonNode(node);

        CtOperatorAssignment op = factory.createOperatorAssignment();
        op.setKind(orig.getKind());
        op.setAssigned(parseExpression(node.getChild(0)));
        op.setAssignment(parseExpression(node.getChild(1)));
        return op;
    }

    private CtLocalVariable<?> parseLocalVariable(ITree node) {
        assert node.getChildren().size() == 2; // this is probably false if there is no initializer

        CtTypeReference tpe = parseTypeReference(node.getChild(0));
        CtExpression<?> initializer = parseExpression(node.getChild(1));
        String name = node.getLabel();
        return factory.createLocalVariable(tpe, name, initializer);
    }

    private CtReturn<?> parseReturn(ITree node) {
        CtReturn<?> ret = factory.createReturn();
        ret.setReturnedExpression(parseExpression(node.getChild(0)));
        return ret;
    }

    private CtIf parseIf(ITree node) {
        // <condition> <then> [else]
        assert node.getChildren().size() == 2 || node.getChildren().size() == 3;

        CtExpression cond = parseExpression(node.getChild(0));
        CtBlock then = parseBlock(node.getChild(1).getChildren());

        CtIf if_ = factory.createIf();
        if_.setCondition(cond);
        if_.setThenStatement(then);

        boolean hasElse = node.getChildren().size()  == 3;
        if (hasElse) {
            CtBlock els = parseBlock(node.getChild(2).getChildren());
            if_.setElseStatement(els);
        }

        return if_;
    }

    private CtFor parseFor(ITree node) {
        // init guard increment body

        Map<CtRole, List<ITree>> nodesByRole = byRole(node.getChildren());
        List<ITree> init = nodesByRole.get(CtRole.FOR_INIT);
        List<ITree> exprs = nodesByRole.get(CtRole.EXPRESSION);
        assert exprs.size() == 1; // there should only be one expression (the guard)
        ITree expr = exprs.get(0);
        List<ITree> update = nodesByRole.get(CtRole.FOR_UPDATE);
        List<ITree> stats = nodesByRole.get(CtRole.STATEMENT);


        CtFor for_ = factory.createFor();

        for_.setForInit(parseStatementListByRole(init, CtRole.FOR_INIT));
        for_.setExpression(parseExpression(expr));
        for_.setForUpdate(parseStatementListByRole(update, CtRole.FOR_UPDATE));
        for_.setBody(parseBlock(stats));

        return for_;
    }

    private Map<CtRole, List<ITree>> byRole(List<ITree> nodes) {
        Map<CtRole, List<ITree>> byRole = new HashMap<>();
        for (ITree node : nodes) {
            CtRole role = getSpoonNode(node).getRoleInParent();
            List<ITree> selectedRoles = byRole.getOrDefault(role, new ArrayList<>());

            if (selectedRoles.isEmpty())
                byRole.put(role, selectedRoles);

            selectedRoles.add(node);
        }
        return byRole;
    }

    private CtThrow parseThrow(ITree node) {
        assert node.getChildren().size() == 1;

        CtThrow throw_ = factory.createThrow();
        CtExpression expr = parseExpression(node.getChild(0));
        throw_.setThrownExpression(expr);

        return throw_;
    }

    private CtExpression parseExpression(ITree node) {
        CtElement spoonNode = getSpoonNode(node);

        if (spoonNode instanceof CtBinaryOperator) {
            CtExpression left = parseExpression(node.getChild(0));
            CtExpression right = parseExpression(node.getChild(1));
            return factory.createBinaryOperator(left, right, BinaryOperatorKind.valueOf(node.getLabel()));
        } else if (spoonNode instanceof CtVariableRead<?>) {
            return (CtVariableRead<?>) spoonNode.clone();
        } else if (spoonNode instanceof  CtConstructorCall<?>) {
            return parseConstructorCall(node);
        } else if (spoonNode instanceof  CtLiteral<?>) {
            return (CtLiteral) spoonNode.clone();
        } else if (spoonNode instanceof CtVariableWrite) {
            return (CtVariableWrite) spoonNode.clone();
        }

        throw new IllegalStateException("unhandled expression: " + spoonNode.getShortRepresentation());
    }

    private CtConstructorCall parseConstructorCall(ITree node) {
        assert node.getChildren().size() > 0;

        CtConstructorCall<?> spoonNode = (CtConstructorCall<?>) getSpoonNode(node);
        CtTypeReference<?> receiverType = spoonNode.getType().clone();
        receiverType.setSimplyQualified(spoonNode.getType().isSimplyQualified());

        CtExpression[] args = new CtExpression[node.getChildren().size()];
        for (int i = 0; i < node.getChildren().size(); i++) {
            args[i] = parseExpression(node.getChild(i));
        }

        CtConstructorCall constrCall = factory.createConstructorCall(receiverType, args);
        return constrCall;
    }

    private CtUnaryOperator<?> parseUnaryOperator(ITree node) {
        assert node.getChildren().size() == 1;

        CtExpression expr = parseExpression(node.getChild(0));
        CtUnaryOperator<?> op = factory.createUnaryOperator();
        op.setKind(UnaryOperatorKind.valueOf(node.getLabel()));
        op.setOperand(expr);
        return op;
    }


    private static CtTypeReference<?> parseTypeReference(ITree gtNode) {
        return (CtTypeReference<?>) getSpoonNode(gtNode).clone();
    }

    private static CtElement getSpoonNode(ITree gtNode) {
        return (CtElement) gtNode.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);
    }
}
