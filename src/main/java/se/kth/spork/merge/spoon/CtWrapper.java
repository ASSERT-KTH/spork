package se.kth.spork.merge.spoon;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtPackage;

import java.util.Objects;

/**
 * A simple wrapper class for a Spoon CtElement. The reason it is needed is that the 3DM merge implementation
 * uses lookup tables, and CtElements have very heavy-duty equals and hash functions. For the purpose of 3DM merge,
 * only reference equality is needed, not deep equality.
 *
 * This class should only be instantiated with the CtWrapperFactory singleton.
 */
public class CtWrapper {
    private final CtElement element;
    private final long key;

    CtWrapper(CtElement element, long key) {
        this.element = element;
        this.key = key;
    }

    public CtElement getElement() {
        return element;
    }

    public CtWrapper getParent() {
        if (element instanceof CtClass)
            return null;
        return WrapperFactory.wrap(element.getParent());
    }

    @Override
    public String toString() {
        if (element == null)
            return "null";

        String longRep = element.toString();
        if (longRep.contains("\n")) {
            String[] shortRep = element.getShortRepresentation().split("\\.");
            return shortRep[shortRep.length - 1];
        }
        return longRep;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CtWrapper ctWrapper = (CtWrapper) o;
        return key == ctWrapper.key;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
