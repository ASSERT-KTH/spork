package se.kth.spork.spoon;

import se.kth.spork.util.Pair;
import spoon.reflect.path.CtRole;

import java.util.*;

/**
 * Class representing some form of value in a Spoon node, along with the role the value has. This is for
 * example the name of a method, or the value in a literal.
 *
 * @author Simon Larsén
 */
class RoledValues extends ArrayList<RoledValue> {

    public RoledValues() {
        super();
    }

    public RoledValues(Collection<? extends RoledValue> collection) {
        super(collection);
    }

    public void add(CtRole role, Object value) {
        add(new RoledValue(role, value));
    }

    public RoledValue set(int i, CtRole role, Object value) {
        return set(i, new RoledValue(role, value));
    }
}