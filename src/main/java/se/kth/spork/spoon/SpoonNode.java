package se.kth.spork.spoon;

import se.kth.spork.base3dm.ListNode;
import spoon.reflect.declaration.CtElement;

public interface SpoonNode extends ListNode {
    CtElement getElement();

    SpoonNode getParent();
}
