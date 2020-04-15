package se.kth.spork.spoon.wrappers;

import se.kth.spork.base3dm.ListNode;
import se.kth.spork.base3dm.Revision;
import spoon.reflect.declaration.CtElement;

public interface SpoonNode extends ListNode {
    CtElement getElement();

    SpoonNode getParent();

    Revision getRevision();
}
