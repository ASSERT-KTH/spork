package se.kth.spork.spoon.pcsinterpreter

import se.kth.spork.base3dm.ChangeSet
import se.kth.spork.spoon.conflict.ContentConflictHandler
import se.kth.spork.spoon.conflict.StructuralConflictHandler
import se.kth.spork.spoon.matching.SpoonMapping
import se.kth.spork.spoon.wrappers.RoledValues
import se.kth.spork.spoon.wrappers.SpoonNode
import spoon.reflect.declaration.CtElement

/**
 * Convert a merged PCS structure into a Spoon tree.
 *
 * @param delta The merged change set.
 * @param baseLeft A tree matching between the base revision and the left revision.
 * @param baseRight A tree matching between the base revision and the right revision.
 * @param structuralConflictHandlers A potentially empty list of structural conflict handlers.
 * @return A pair on the form (tree, numConflicts).
 */
fun fromMergedPcs(
    base: ChangeSet<SpoonNode, RoledValues>,
    delta: ChangeSet<SpoonNode, RoledValues>,
    baseLeft: SpoonMapping,
    baseRight: SpoonMapping,
    structuralConflictHandlers: List<StructuralConflictHandler>,
    contentConflictHandlers: List<ContentConflictHandler>,
    diff3: Boolean,
): Pair<CtElement?, Int> {
    val sporkTreeBuilder = SporkTreeBuilder(base, delta, baseLeft, baseRight, structuralConflictHandlers, diff3)
    val sporkTreeRoot = sporkTreeBuilder.buildTree()

    // this is a bit of a hack, get any used environment such that the SpoonTreeBuilder can copy environment details
    val oldEnv = sporkTreeRoot
        .children[0]
        .node
        .element
        .factory
        .environment
    val spoonTreeBuilder = SpoonTreeBuilder(baseLeft, baseRight, oldEnv, contentConflictHandlers, diff3)
    val spoonTreeRoot = spoonTreeBuilder.build(sporkTreeRoot)
    return Pair(
        spoonTreeRoot,
        sporkTreeBuilder.numStructuralConflicts() + spoonTreeBuilder.numContentConflicts,
    )
}
