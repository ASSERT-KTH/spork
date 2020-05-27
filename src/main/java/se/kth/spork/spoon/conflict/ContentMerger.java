package se.kth.spork.spoon.conflict;

import se.kth.spork.base3dm.Content;
import se.kth.spork.spoon.wrappers.RoledValue;
import se.kth.spork.spoon.wrappers.RoledValues;
import se.kth.spork.spoon.wrappers.SpoonNode;
import se.kth.spork.util.Pair;
import se.kth.spork.util.Triple;
import spoon.reflect.path.CtRole;

import java.util.*;

/**
 * A class for dealing with merging of content.
 *
 * @author Simon Larsén
 */
public class ContentMerger {
    private final Map<CtRole, ContentConflictHandler> conflictHandlers;

    /**
     * @param conflictHandlers A list of conflict handlers. There may only be one handler per role.
     */
    public ContentMerger(List<ContentConflictHandler> conflictHandlers) {
        this.conflictHandlers = new HashMap<>();
        for (ContentConflictHandler handler : conflictHandlers) {
            if (this.conflictHandlers.containsKey(handler.getRole())) {
                throw new IllegalArgumentException("duplicate handler for role " + handler.getRole());
            }
            this.conflictHandlers.put(handler.getRole(), handler);
        }
    }

    /**
     * @param nodeContents The contents associated with this node.
     * @return A pair of merged contents and a potentially empty collection of unresolved conflicts.
     */
    @SuppressWarnings("unchecked")
    public Pair<RoledValues, List<ContentConflict>>
    mergedContent(Set<Content<SpoonNode, RoledValues>> nodeContents) {
        if (nodeContents.size() == 1) {
            return Pair.of(nodeContents.iterator().next().getValue(), Collections.emptyList());
        }

        _ContentTriple revisions = getContentRevisions(nodeContents);
        Optional<Content<SpoonNode, RoledValues>> baseOpt = revisions.first;
        RoledValues leftRoledValues = revisions.second.getValue();
        RoledValues rightRoledValues = revisions.third.getValue();

        // NOTE: It is important that the left values are copied,
        // by convention the LEFT values should be put into the tree whenever a conflict cannot be resolved
        RoledValues mergedRoledValues = new RoledValues(leftRoledValues);

        assert leftRoledValues.size() == rightRoledValues.size();

        Deque<ContentConflict> unresolvedConflicts = new ArrayDeque<>();

        for (int i = 0; i < leftRoledValues.size(); i++) {
            int finalI = i;
            RoledValue leftRv = leftRoledValues.get(i);
            RoledValue rightRv = rightRoledValues.get(i);
            assert leftRv.getRole() == rightRv.getRole();

            Optional<RoledValue> baseRv = baseOpt.map(Content::getValue).map(rv -> rv.get(finalI));
            Optional<Object> baseValOpt = baseRv.map(RoledValue::getValue);

            CtRole role = leftRv.getRole();
            Object leftVal = leftRv.getValue();
            Object rightVal = rightRv.getValue();

            if (leftRv.equals(rightRv)) {
                // this pair cannot possibly conflict
                continue;
            }

            // left and right pairs differ and are so conflicting
            // we add them as a conflict, but will later remove it if the conflict can be resolved
            unresolvedConflicts.push(new ContentConflict(
                    role,
                    baseOpt.map(Content::getValue).map(rv -> rv.get(finalI)),
                    leftRv,
                    rightRv));


            Optional<?> merged = Optional.empty();

            // sometimes a value can be partially merged (e.g. modifiers), and then we want to be
            // able to set the merged value, AND flag a conflict.
            boolean conflictPresent = false;

            // if either value is equal to base, we keep THE OTHER one
            if (baseValOpt.isPresent() && baseValOpt.get().equals(leftVal)) {
                merged = Optional.of(rightVal);
            } else if (baseValOpt.isPresent() && baseValOpt.get().equals(rightVal)) {
                merged = Optional.of(leftVal);
            } else {
                // non-trivial conflict, check if there is a conflict handler for this role
                ContentConflictHandler handler = conflictHandlers.get(role);
                if (handler != null) {
                    Pair<Optional<Object>, Boolean> result = handler.handleConflict(
                            baseValOpt,
                            leftVal,
                            rightVal,
                            baseOpt.map(c -> c.getValue().getElement()),
                            leftRoledValues.getElement(),
                            rightRoledValues.getElement());
                    merged = result.first;
                    conflictPresent = result.second;
                }
            }


            if (merged.isPresent()) {
                mergedRoledValues.set(i, role, merged.get());

                if (!conflictPresent)
                    unresolvedConflicts.pop();
            }
        }

        return Pair.of(mergedRoledValues, new ArrayList<>(unresolvedConflicts));
    }

    private static _ContentTriple getContentRevisions(Set<Content<SpoonNode, RoledValues>> contents) {
        Content<SpoonNode, RoledValues> base = null;
        Content<SpoonNode, RoledValues> left = null;
        Content<SpoonNode, RoledValues> right = null;

        for (Content<SpoonNode, RoledValues> cnt : contents) {
            switch (cnt.getRevision()) {
                case BASE:
                    base = cnt;
                    break;
                case LEFT:
                    left = cnt;
                    break;
                case RIGHT:
                    right = cnt;
                    break;
            }
        }

        if (left == null || right == null)
            throw new IllegalArgumentException("Expected at least left and right revisions, got: " + contents);

        return new _ContentTriple(Optional.ofNullable(base), left, right);
    }

    // this is just a type alias to declutter the getContentRevisions method header
    private static class _ContentTriple extends Triple<
            Optional<Content<SpoonNode, RoledValues>>,
            Content<SpoonNode, RoledValues>,
            Content<SpoonNode, RoledValues>> {
        public _ContentTriple(
                Optional<Content<SpoonNode, RoledValues>> first,
                Content<SpoonNode, RoledValues> second,
                Content<SpoonNode, RoledValues> third) {
            super(first, second, third);
        }
    }
}
