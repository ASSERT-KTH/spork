package se.kth.spork.merge.spoon;

import spoon.reflect.declaration.CtElement;

public interface SpoonNode {
    CtElement getElement();

    SpoonNode getParent();
}
