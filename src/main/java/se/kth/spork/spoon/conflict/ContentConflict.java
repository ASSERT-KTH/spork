package se.kth.spork.spoon.conflict;

import java.util.Optional;
import se.kth.spork.spoon.wrappers.RoledValue;
import spoon.reflect.path.CtRole;

public class ContentConflict {
    public static final String METADATA_KEY = "SPORK_CONTENT_CONFLICT";

    private final CtRole role;
    private final Optional<RoledValue> base;
    private final RoledValue left;
    private final RoledValue right;

    public ContentConflict(
            CtRole role, Optional<RoledValue> base, RoledValue left, RoledValue right) {
        this.role = role;
        this.base = base;
        this.left = left;
        this.right = right;
    }

    public CtRole getRole() {
        return role;
    }

    public Optional<RoledValue> getBase() {
        return base;
    }

    public RoledValue getLeft() {
        return left;
    }

    public RoledValue getRight() {
        return right;
    }
}
