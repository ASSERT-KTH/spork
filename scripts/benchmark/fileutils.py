"""Utility functions for files and directories."""
import collections
import enum
import pathlib
from typing import List

import git

from . import gitutils


class Revision(enum.Enum):
    BASE = enum.auto()
    LEFT = enum.auto()
    RIGHT = enum.auto()
    ACTUAL_MERGE = enum.auto()


def create_merge_dirs(merge_dir_base: pathlib.Path, merge_scenarios: List[gitutils.MergeScenario]):
    """Create merge directories based on the provided merge scenarios. For each merge scenario A,
    a merge directory A is created. For each file to be merged, a subdirectory with base, left, right and
    expected merge revisions are created.
    """
    for merge in merge_scenarios:
        merge_dir = merge_dir_base / merge.result.hexsha
        merge_dir.mkdir()

        diff_left = merge.base.diff(merge.left)
        diff_right = merge.base.diff(merge.right)
        diff_result = merge.base.diff(merge.result)

        mapping = collections.defaultdict(dict)
        _insert(Revision.LEFT, diff_left, mapping)
        _insert(Revision.RIGHT, diff_right, mapping)
        _insert(Revision.ACTUAL_MERGE, diff_result, mapping)

        for key, val in mapping.items():
            scenario_dir = merge_dir / key
            scenario_dir.mkdir()

            base_content = val.get(Revision.BASE) or val.get("result")
            expected_content = val.get("result") or base_content

            _write_blob_to_file(
                scenario_dir / "Left.java", val.get("left") or base_content
            )
            _write_blob_to_file(
                scenario_dir / "Right.java", val.get("right") or base_content
            )
            _write_blob_to_file(scenario_dir / "Base.java", base_content)
            _write_blob_to_file(scenario_dir / "Expected.java", expected_content)


def _insert(revision: Revision, diffs: List[git.Commit], mapping):
    for d in diffs:
        base = d.a_blob
        rev = d.b_blob

        if not base or not rev:  # deletion or addition of a file, not interesting
            assert d.change_type in ["A", "D"]
            continue

        if base and base.name.endswith(".java") or rev and rev.name.endswith(".java"):
            mapping[d.a_blob.hexsha][Revision.BASE] = base
            mapping[d.a_blob.hexsha][revision] = rev


def _write_blob_to_file(filepath, blob):
    filepath.write_bytes(blob.data_stream[-1].read())
