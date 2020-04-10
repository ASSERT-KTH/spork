"""Module for functions that gather data about a merge."""
import collections
import pathlib
import enum
from typing import Iterable, Iterable

from . import evaluate
from . import run


class EvalAttrName(enum.Enum):
    merge_dir = "merge_dir"
    merge_cmd = "merge_cmd"
    outcome = "outcome"
    gumtree_diff_size = "gumtree_diff_size"
    git_diff_size = "git_diff_size"
    norm_diff_size = "norm_diff_size"
    num_conflicts = "num_conflicts"
    conflict_size = "conflict_size"
    runtime = "runtime"


NUMERICAL_EVAL_ATTR_NAMES = tuple(
    e.value
    for e in (
        EvalAttrName.gumtree_diff_size,
        EvalAttrName.git_diff_size,
        EvalAttrName.norm_diff_size,
        EvalAttrName.num_conflicts,
        EvalAttrName.conflict_size,
        EvalAttrName.runtime,
    )
)

MergeEvaluation = collections.namedtuple(
    "MergeEvaluation", [e.value for e in EvalAttrName],
)


def run_and_evaluate(
    merge_dirs: Iterable[pathlib.Path],
    merge_commands: Iterable[str],
    base_merge_dir: pathlib.Path,
) -> Iterable[MergeEvaluation]:
    for merge_cmd in merge_commands:
        for merge_result in run.run_file_merges(merge_dirs, merge_cmd):
            yield evaluation_result(merge_result, base_merge_dir)


def evaluation_result(merge_result: run.MergeResult, base_merge_dir: pathlib.Path):
    """Gather evaluation results from the provided merge result."""
    gumtree_diff_size = -1
    git_diff_size = -1
    norm_diff_size = -1
    conflict_size = -1
    num_conflicts = -1

    if merge_result.outcome != run.MergeOutcome.FAIL:
        conflicts = evaluate.extract_conflicts(merge_result.merge_file)
        conflict_size = sum(c.num_lines for c in conflicts)
        num_conflicts = len(conflicts)

        if not num_conflicts:
            expected_file = merge_result.merge_dir / "Expected.java"
            gumtree_diff_size = len(
                evaluate.gumtree_edit_script(expected_file, merge_result.merge_file)
            )
            git_diff_size = len(
                evaluate.git_diff_edit_script(expected_file, merge_result.merge_file)
            )
            norm_diff_size = evaluate.normalized_comparison(expected_file, merge_result.merge_file)


    return MergeEvaluation(
        merge_dir=merge_result.merge_dir.relative_to(base_merge_dir),
        merge_cmd=merge_result.merge_cmd,
        outcome=merge_result.outcome,
        gumtree_diff_size=gumtree_diff_size,
        git_diff_size=git_diff_size,
        norm_diff_size=int(norm_diff_size),
        conflict_size=conflict_size,
        num_conflicts=num_conflicts,
        runtime=merge_result.runtime,
    )

