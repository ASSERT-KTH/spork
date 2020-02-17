package se.kth.spork.merge.spoon;

import spoon.reflect.path.CtRole;

import java.util.Objects;

/**
 * Class representing some form of value in a Spoon node, along with the role the value has. This is for
 * example the name of a method, or the value in a literal.
 *
 * @author Simon Larsén
 */
class RoledValue {
    private final Object value;
    private final CtRole role;

    public RoledValue(Object value, CtRole role) {
        this.value = value;
        this.role = role;
    }

    public Object getValue() {
        return value;
    }

    public CtRole getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoledValue roledValue1 = (RoledValue) o;
        return role == roledValue1.role &&
                Objects.equals(value, roledValue1.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, role);
    }

    @Override
    public String toString() {
        return "Value{" +
                "value=" + value +
                ", role=" + role +
                '}';
    }
}