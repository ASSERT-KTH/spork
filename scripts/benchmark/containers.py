"""Containers for storing results of various kinds."""
import dataclasses
import collections
import pathlib
import git
import enum

from typing import Optional


@dataclasses.dataclass(frozen=True)
class MergeScenario:
    result: git.Commit
    base: git.Commit
    left: git.Commit
    right: git.Commit

    @staticmethod
    def from_metainfo(repo: git.Repo, metainfo: "FileMergeMetainfo") -> "MergeScenario":
        return MergeScenario(
            result=repo.commit(metainfo.merge_commit),
            base=repo.commit(metainfo.base_commit),
            left=repo.commit(metainfo.left_commit),
            right=repo.commit(metainfo.right_commit),
        )


@dataclasses.dataclass(frozen=True)
class FileMerge:
    result: git.Blob
    base: Optional[git.Blob]
    left: git.Blob
    right: git.Blob
    from_merge_scenario: MergeScenario

    @staticmethod
    def from_metainfo(repo: git.Repo, metainfo) -> "FileMerge":
        ms = MergeScenario.from_metainfo(repo, metainfo)
        result = ms.result.tree[str(metainfo.merge_filepath)]
        base = ms.base.tree[str(metainfo.base_filepath)]
        left = ms.left.tree[str(metainfo.left_filepath)]
        right = ms.right.tree[str(metainfo.right_filepath)]
        return FileMerge(
            result=result, base=base, left=left, right=right, from_merge_scenario=ms
        )


@dataclasses.dataclass(frozen=True, order=True)
class FileMergeMetainfo:
    merge_commit: str
    expected_blob: str
    expected_filepath: str
    base_commit: str
    base_blob: str
    base_filepath: str
    left_commit: str
    left_blob: str
    left_filepath: str
    right_commit: str
    right_blob: str
    right_filepath: str

    @staticmethod
    def from_file_merge(file_merge: FileMerge) -> "FileMergeMetainfo":
        ms = file_merge.from_merge_scenario

        base_blob = file_merge.base.hexsha if file_merge.base else ""
        base_filepath = file_merge.base.path if file_merge.base else ""

        return FileMergeMetainfo(
            merge_commit=ms.result.hexsha,
            base_commit=ms.base.hexsha,
            left_commit=ms.left.hexsha,
            right_commit=ms.right.hexsha,
            expected_blob=file_merge.result.hexsha,
            expected_filepath=str(file_merge.result.path),
            base_blob=base_blob,
            base_filepath=base_filepath,
            left_blob=file_merge.left.hexsha,
            left_filepath=str(file_merge.left.path),
            right_blob=file_merge.right.hexsha,
            right_filepath=str(file_merge.right.path),
        )


@dataclasses.dataclass(frozen=True, order=True)
class MergeEvaluation:
    merge_dir: pathlib.Path
    merge_cmd: str
    outcome: str
    gumtree_diff_size: int
    git_diff_size: int
    norm_diff_size: int
    num_conflicts: int
    conflict_size: int
    runtime: float
    merge_commit: str
    base_blob: str
    base_lines: int
    left_blob: str
    left_lines: int
    right_blob: str
    right_lines: int
    expected_blob: str
    expected_lines: int


class Revision(enum.Enum):
    BASE = enum.auto()
    LEFT = enum.auto()
    RIGHT = enum.auto()
    ACTUAL_MERGE = enum.auto()


class MergeOutcome:
    CONFLICT = "conflict"
    SUCCESS = "success"
    FAIL = "fail"


@dataclasses.dataclass(frozen=True)
class MergeResult:
    merge_dir: pathlib.Path
    merge_file: pathlib.Path
    base_file: pathlib.Path
    left_file: pathlib.Path
    right_file: pathlib.Path
    expected_file: pathlib.Path
    merge_cmd: str
    outcome: MergeOutcome
    runtime: int


@dataclasses.dataclass(frozen=True, order=True)
class GitMergeResult:
    merge_commit: str
    base_commit: str
    left_commit: str
    right_commit: str
    merge_ok: bool
    build_ok: bool


@dataclasses.dataclass(frozen=True, order=True)
class RuntimeResult:
    merge_commit: str
    base_blob: str
    left_blob: str
    right_blob: str
    parse_time_ms: int
    merge_time_ms: int
    total_time_ms: int
    merge_cmd: str

