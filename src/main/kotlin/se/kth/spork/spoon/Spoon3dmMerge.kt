package se.kth.spork.spoon

import com.github.gumtreediff.matchers.Matcher
import com.github.gumtreediff.matchers.Matchers
import com.github.gumtreediff.tree.ITree
import gumtree.spoon.builder.SpoonGumTreeBuilder
import se.kth.spork.base3dm.ChangeSet
import se.kth.spork.base3dm.Revision
import se.kth.spork.base3dm.resolveRawMerge
import se.kth.spork.spoon.Parser.parse
import se.kth.spork.spoon.conflict.CommentContentHandler
import se.kth.spork.spoon.conflict.IsImplicitHandler
import se.kth.spork.spoon.conflict.IsUpperHandler
import se.kth.spork.spoon.conflict.MethodOrderingConflictHandler
import se.kth.spork.spoon.conflict.ModifierHandler
import se.kth.spork.spoon.conflict.OptimisticInsertInsertHandler
import se.kth.spork.spoon.conflict.StructuralConflict
import se.kth.spork.spoon.matching.MappingRemover.Companion.removeFromMappings
import se.kth.spork.spoon.matching.SpoonMapping.Companion.fromGumTreeMapping
import se.kth.spork.spoon.matching.createClassRepresentativesMapping
import se.kth.spork.spoon.pcsinterpreter.PcsInterpreter
import se.kth.spork.spoon.wrappers.NodeFactory
import se.kth.spork.spoon.wrappers.NodeFactory.clearNonRevisionMetadata
import se.kth.spork.spoon.wrappers.NodeFactory.forceWrap
import se.kth.spork.spoon.wrappers.NodeFactory.virtualRoot
import se.kth.spork.util.LazyLogger
import se.kth.spork.util.LineBasedMerge
import se.kth.spork.util.Pair
import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.CtExecutable
import spoon.reflect.declaration.CtField
import spoon.reflect.declaration.CtImport
import spoon.reflect.declaration.CtModule
import spoon.reflect.declaration.CtType
import spoon.reflect.declaration.CtTypeMember
import java.lang.IllegalStateException
import java.nio.file.Path
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet

/**
 * Spoon specialization of the 3DM merge algorithm.
 *
 * @author Simon Larsén
 */
object Spoon3dmMerge {
    private val LOGGER = LazyLogger(Spoon3dmMerge::class.java)

    /**
     * Merge the left and right revisions with an AST-based merge.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    fun merge(base: Path, left: Path, right: Path): Pair<CtModule, Int> {
        val start = System.nanoTime()

        // PARSING PHASE
        LOGGER.info { "Parsing files to Spoon trees" }
        val baseTree = parse(base)
        val leftTree = parse(left)
        val rightTree = parse(right)
        val end = System.nanoTime()
        val timeDelta = (end - start).toDouble() / 1e9
        LOGGER.info { "Parsed files in $timeDelta seconds" }
        return merge(baseTree, leftTree, rightTree)
    }

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should
     * be the best common ancestor of left and right.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @param baseMatcher Function that returns a matcher for the base-to-left and base-to-right
     * matchings.
     * @param leftRightMatcher Function that returns a matcher for the left-to-right matching.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    fun <T : CtElement> merge(
        base: T,
        left: T,
        right: T,
        baseMatcher: (ITree, ITree) -> Matcher,
        leftRightMatcher: (ITree, ITree) -> Matcher
    ): Pair<T, Int> {
        val start = System.nanoTime()

        // MATCHING PHASE
        LOGGER.info { "Converting to GumTree trees" }
        val baseGumtree = SpoonGumTreeBuilder().getTree(base)
        val leftGumtree = SpoonGumTreeBuilder().getTree(left)
        val rightGumtree = SpoonGumTreeBuilder().getTree(right)
        LOGGER.info { "Matching trees with GumTree" }
        val baseLeftGumtreeMatch = baseMatcher(baseGumtree, leftGumtree)
        val baseRightGumtreeMatch = baseMatcher(baseGumtree, rightGumtree)
        val leftRightGumtreeMatch = leftRightMatcher(leftGumtree, rightGumtree)
        LOGGER.info { "Converting GumTree matches to Spoon matches" }
        val baseLeft = fromGumTreeMapping(baseLeftGumtreeMatch.mappings)
        val baseRight = fromGumTreeMapping(baseRightGumtreeMatch.mappings)
        val leftRight = fromGumTreeMapping(leftRightGumtreeMatch.mappings)

        // 3DM PHASE
        LOGGER.info { "Mapping nodes to class representatives" }
        var classRepMap = createClassRepresentativesMapping(
            base, left, right, baseLeft, baseRight, leftRight
        )
        LOGGER.info { "Converting Spoon trees to PCS triples" }
        val t0 = PcsBuilder.fromSpoon(base, Revision.BASE)
        val t1 = PcsBuilder.fromSpoon(left, Revision.LEFT)
        val t2 = PcsBuilder.fromSpoon(right, Revision.RIGHT)
        LOGGER.info { "Computing raw PCS merge" }
        var delta = ChangeSet(
            classRepMap, ::getContent, t0, t1, t2
        )
        val t0Star = ChangeSet(
            classRepMap, ::getContent, t0
        )
        LOGGER.info { "Resolving final PCS merge" }
        resolveRawMerge(t0Star, delta)
        val rootConflictingNodes = StructuralConflict.extractRootConflictingNodes(delta.structuralConflicts)
        if (!rootConflictingNodes.isEmpty()) {
            LOGGER.info { "Root conflicts detected, restarting merge" }
            LOGGER.info { "Removing root conflicting nodes from tree matchings" }
            removeFromMappings(
                rootConflictingNodes, baseLeft, baseRight, leftRight
            )
            LOGGER.info { "Mapping nodes to class representatives" }
            classRepMap = createClassRepresentativesMapping(
                base, left, right, baseLeft, baseRight, leftRight
            )
            LOGGER.info { "Computing raw PCS merge" }
            delta = ChangeSet(classRepMap, ::getContent, t0, t1, t2)
            LOGGER.info { "Resolving final PCS merge" }
            resolveRawMerge(t0Star, delta)
        }

        // INTERPRETER PHASE
        LOGGER.info { "Interpreting resolved PCS merge" }
        val structuralConflictHandlers = Arrays.asList(
            MethodOrderingConflictHandler(), OptimisticInsertInsertHandler()
        )
        val contentConflictHandlers = Arrays.asList(
            IsImplicitHandler(),
            ModifierHandler(),
            IsUpperHandler(),
            CommentContentHandler()
        )
        val merge = PcsInterpreter.fromMergedPcs(
            delta,
            baseLeft,
            baseRight,
            structuralConflictHandlers,
            contentConflictHandlers
        )
        // we can be certain that the merge tree has the same root type as the three constituents,
        // so this cast is safe
        val mergeTree = merge.first as T
        val numConflicts = merge.second
        val metadataElementConflicts = mergeMetadataElements(mergeTree, base, left, right)
        LOGGER.info { "Checking for duplicated members" }
        val duplicateMemberConflicts = eliminateDuplicateMembers(mergeTree)
        LOGGER.info { "Merged in " + (System.nanoTime() - start).toDouble() / 1e9 + " seconds" }
        return Pair.of(
            mergeTree, numConflicts + metadataElementConflicts + duplicateMemberConflicts
        )
    }

    /**
     * Merge the left and right revisions. The base revision is used for computing edits, and should
     * be the best common ancestor of left and right.
     *
     *
     * Uses the full GumTree matcher for base-to-left and base-to-right, and the XY matcher for
     * left-to-right matchings.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @return A pair on the form (mergeTree, numConflicts).
     */
    fun <T : CtElement> merge(base: T, left: T, right: T): Pair<T, Int> {
        return merge(base, left, right, ::matchTrees, ::matchTreesXY)
    }

    private fun mergeMetadataElements(
        mergeTree: CtElement,
        base: CtElement,
        left: CtElement,
        right: CtElement
    ): Int {
        var numConflicts = 0
        if (base.getMetadata(Parser.IMPORT_STATEMENTS) != null) {
            LOGGER.info { "Merging import statements" }
            val mergedImports = mergeImportStatements(base, left, right)
            mergeTree.putMetadata<CtElement>(Parser.IMPORT_STATEMENTS, mergedImports)
        }
        if (base.getMetadata(Parser.COMPILATION_UNIT_COMMENT) != null) {
            LOGGER.info { "Merging compilation unit comments" }
            val cuCommentMerge = mergeCuComments(base, left, right)
            numConflicts += cuCommentMerge.second
            mergeTree.putMetadata<CtElement>(Parser.COMPILATION_UNIT_COMMENT, cuCommentMerge.first)
        }
        return numConflicts
    }

    private fun eliminateDuplicateMembers(merge: CtElement): Int {
        val types = merge.getElements { _: CtType<*> -> true }
        var numConflicts = 0
        for (type in types) {
            numConflicts += eliminateDuplicateMembers(type)
        }
        return numConflicts
    }

    private fun eliminateDuplicateMembers(type: CtType<*>): Int {
        val members: List<CtTypeMember> = ArrayList(type.typeMembers)
        var numConflicts = 0

        val getMemberName = { member: CtTypeMember ->
            when (member) {
                is CtExecutable<*> -> member.signature
                is CtField<*> -> member.simpleName
                is CtType<*> -> member.qualifiedName
                else -> throw IllegalStateException("unknown member type ${member.javaClass}")
            }
        }

        val duplicates: List<kotlin.Pair<CtTypeMember, CtTypeMember>> =
            members.groupBy(getMemberName).filterValues { it.size == 2 }.values.map {
                kotlin.Pair(it[0], it[1])
            }

        for ((left, right) in duplicates) {
            LOGGER.info { "Merging duplicated member ${getMemberName(left)}" }
            left.descendantIterator().forEachRemaining(NodeFactory::clearNonRevisionMetadata)

            left.descendantIterator().forEachRemaining(NodeFactory::clearNonRevisionMetadata)
            right
                .descendantIterator()
                .forEachRemaining(NodeFactory::clearNonRevisionMetadata)
            val dummyBase = left.clone() as CtTypeMember
            dummyBase.setParent(type)
            dummyBase.directChildren.forEach(CtElement::delete)

            // we forcibly set the virtual root as parent, as the real parent of these members
            // is outside of the current scope
            clearNonRevisionMetadata(left)
            clearNonRevisionMetadata(right)
            clearNonRevisionMetadata(dummyBase)
            forceWrap(left, virtualRoot)
            forceWrap(right, virtualRoot)
            forceWrap(dummyBase, virtualRoot)

            // use the full gumtree matcher as both base matcher and left-to-right matcher
            val mergePair = merge(
                dummyBase,
                left,
                right,
                ::matchTrees,
                ::matchTrees
            )
            numConflicts += mergePair.second
            val mergedMember = mergePair.first
            left.delete()
            right.delete()

            // badness in the Spoon API: addTypeMember returns a generic type that depends only on the
            // static type of the returned expression. So we must store the returned expression and declare
            // the type, or Kotlin gets grumpy.
            val dontcare: CtType<*> = type.addTypeMember(mergedMember)
        }

        return numConflicts
    }

    /**
     * Perform a line-based merge of the compilation unit comments.
     *
     * @return A pair with the merge and the amount of conflicts.
     */
    private fun mergeCuComments(
        base: CtElement,
        left: CtElement,
        right: CtElement
    ): Pair<String, Int> {
        val baseComment = getCuComment(base)
        val leftComment = getCuComment(left)
        val rightComment = getCuComment(right)
        return LineBasedMerge.merge(baseComment, leftComment, rightComment)
    }

    private fun getCuComment(mod: CtElement): String = mod.getMetadata(Parser.COMPILATION_UNIT_COMMENT) as String

    /**
     * Merge import statements from base, left and right. Import statements are expected to be
     * attached to each tree's root node metadata with the [Parser.IMPORT_STATEMENTS] key.
     *
     *
     * This method naively merges import statements by respecting additions and deletions from
     * both revisions.
     *
     * @param base The base revision.
     * @param left The left revision.
     * @param right The right revision.
     * @return A merged import list, sorted in lexicographical order.
     */
    private fun mergeImportStatements(
        base: CtElement,
        left: CtElement,
        right: CtElement
    ): List<CtImport> {
        val baseImports = HashSet(base.getMetadata(Parser.IMPORT_STATEMENTS) as Collection<CtImport>)
        val leftImports = HashSet(left.getMetadata(Parser.IMPORT_STATEMENTS) as Collection<CtImport>)
        val rightImports = HashSet(right.getMetadata(Parser.IMPORT_STATEMENTS) as Collection<CtImport>)

        // first create union, this respects all additions
        val rawMerge = baseImports + leftImports + rightImports

        // now remove all elements that were deleted
        val baseLeftDeletions = baseImports - leftImports
        val baseRightDeletions = baseImports - rightImports
        val merge = rawMerge - baseLeftDeletions - baseRightDeletions
        return merge.toList().sortedBy(CtImport::toString)
    }

    private fun matchTrees(src: ITree, dst: ITree): Matcher {
        val matcher = Matchers.getInstance().getMatcher(src, dst)
        matcher.match()
        return matcher
    }

    private fun matchTreesXY(src: ITree, dst: ITree): Matcher {
        val matcher = Matchers.getInstance().getMatcher("xy", src, dst)
        matcher.match()
        return matcher
    }

    init {
        System.setProperty("gt.xym.sim", "0.7")
    }
}
