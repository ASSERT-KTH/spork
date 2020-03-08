package se.kth.spork.spoon;

import spoon.reflect.path.CtRole;

import java.util.Optional;

public class ContentConflict {
    public static final String METADATA_KEY = "SPORK_CONTENT_CONFLICT";

    private final CtRole role;
    private final Optional<Object> base;
    private final Object left;
    private final Object right;

    public ContentConflict(CtRole role, Optional<Object> base, Object left, Object right) {
        this.role = role;
        this.base = base;
        this.left = left;
        this.right = right;
    }

    public CtRole getRole() {
        return role;
    }

    public Optional<Object> getBase() {
        return base;
    }

    public Object getLeft() {
        return left;
    }

    public Object getRight() {
        return right;
    }
}
