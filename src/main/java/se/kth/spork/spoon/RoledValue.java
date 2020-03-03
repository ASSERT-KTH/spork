package se.kth.spork.spoon;

import spoon.reflect.path.CtRole;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class representing some form of value in a Spoon node, along with the role the value has. This is for
 * example the name of a method, or the value in a literal.
 *
 * @author Simon Larsén
 */
class RoledValue {
    private final Object value;
    private final CtRole role;

    // secondary values can be, for example, modifiers for classes and fields or bounds on type parameters
    private List<RoledValue> secondaryValues;

    public RoledValue(Object value, CtRole role) {
        this.value = value;
        this.role = role;
        this.secondaryValues = new ArrayList<>();
    }

    public Object getValue() {
        return value;
    }

    public CtRole getRole() {
        return role;
    }

    public void addSecondaryValue(Object value, CtRole role) {
        secondaryValues.add(new RoledValue(value, role));
    }

    public List<RoledValue> getSecondaryValues() {
        return Collections.unmodifiableList(secondaryValues);
    }

    public boolean hasSecondaryValues() {
        return !secondaryValues.isEmpty();
    }

    public RoledValue getSecondaryByRole(CtRole role) {
        Optional<RoledValue> val = secondaryValues.stream().filter(v -> v.getRole() == role).findFirst();
        if (!val.isPresent()) {
            throw new RuntimeException("No secondary value with role " + role);
        }
        return val.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoledValue roledValue1 = (RoledValue) o;
        return role == roledValue1.role &&
                Objects.equals(value, roledValue1.value) &&
                Objects.equals(secondaryValues, roledValue1.secondaryValues);
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