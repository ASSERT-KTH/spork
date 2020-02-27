package se.kth.spork.merge.spoon;

import se.kth.spork.merge.ListNode;
import spoon.reflect.declaration.CtElement;

public interface SpoonNode extends ListNode {
    CtElement getElement();

    SpoonNode getParent();
}
