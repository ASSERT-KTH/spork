package se.kth.spork.spoon.wrappers;

import spoon.reflect.path.CtRole;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class RoledValue {
    private final CtRole role;
    private final Object value;
    private final Map<Key, Object> metadata;

    public enum Key {
        RAW_CONTENT
    }

    public RoledValue(CtRole role, Object value) {
        this.role = role;
        this.value = value;
        metadata = new HashMap<>();
    }

    public CtRole getRole() {
        return role;
    }

    public Object getValue() {
        return value;
    }

    public void putMetadata(Key key, Object value) {
        metadata.put(key, value);
    }

    public Object getMetadata(Key key) {
        return metadata.get(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoledValue that = (RoledValue) o;
        return role == that.role &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, value);
    }
}
