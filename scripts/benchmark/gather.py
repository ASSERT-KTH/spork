"""Module for functions that gather data about a merge."""
import collections
import pathlib
from typing import Iterable, Iterable

from . import evaluate
from . import run

MergeEvaluation = collections.namedtuple(
    "MergeEvaluation",
    "merge_dir merge_cmd outcome gt_diff_size git_diff_size runtime".split(),
)


def run_and_evaluate(
    merge_dirs: Iterable[pathlib.Path], merge_commands: Iterable[str]
) -> Iterable[MergeEvaluation]:
    for merge_cmd in merge_commands:
        for merge_result in run.run_file_merges(merge_dirs, merge_cmd):
            yield evaluation_result(merge_result)


def evaluation_result(merge_result: run.MergeResult):
    """Gather evaluation results from the provided merge result."""
    gumtree_diff_size = -1
    git_diff_size = -1

    if merge_result.outcome == run.MergeOutcome.SUCCESS:
        expected_file = merge_result.merge_dir / "Expected.java"
        gumtree_diff_size = len(
            evaluate.gumtree_edit_script(expected_file, merge_result.merge_file)
        )
        git_diff_size = len(
            evaluate.git_diff_edit_script(expected_file, merge_result.merge_file)
        )

    return MergeEvaluation(
        merge_dir=merge_result.merge_dir,
        merge_cmd=merge_result.merge_cmd,
        outcome=merge_result.outcome,
        gt_diff_size=gumtree_diff_size,
        git_diff_size=git_diff_size,
        runtime=merge_result.runtime,
    )

