"""Module with some Git utility functions and classes."""
import sys
import enum
import dataclasses
import os
import itertools
import pathlib
import shutil
from typing import List, Optional, Mapping, Sequence, Tuple

import git
import daiquiri

LOGGER = daiquiri.getLogger(__name__)


@dataclasses.dataclass(frozen=True)
class MergeScenario:
    result: git.Commit
    base: git.Commit
    left: git.Commit
    right: git.Commit


class Revision(enum.Enum):
    BASE = enum.auto()
    LEFT = enum.auto()
    RIGHT = enum.auto()
    ACTUAL_MERGE = enum.auto()


def extract_merge_scenarios(
    repo: git.Repo, limit: Optional[int] = None
) -> List[MergeScenario]:
    """Extract merge scenarios from a repo.

    Args:
        repo: A Git repo.
        limit: Maximum amount of merge scenarios to return.
    Returns:
        A list of merge scenarios.
    """
    merge_commits = [
        commit for commit in repo.iter_commits() if len(commit.parents) == 2
    ]

    merge_scenarios = []

    for merge in merge_commits:
        left, right = merge.parents
        base = repo.merge_base(*merge.parents)

        if not base:
            LOGGER.warning(
                f"No merge base for commits {left.hexsha} and {right.hexsha}"
            )
            continue
        elif len(base) > 1:
            LOGGER.warning(
                f"Ambiguous merge base for commits {left.hexsha} and {right.hexsha}: {base}"
            )
            continue

        merge_scenarios.append(MergeScenario(merge, base[0], left, right))

    return merge_scenarios if limit is None else merge_scenarios[:limit]


def extract_all_conflicting_files(
    repo: git.Repo, merge_scenarios: Sequence[MergeScenario],
) -> List[Tuple[git.Commit, List[Mapping[Revision, git.Blob]]]]:
    return [(ms.result, extract_conflicting_files(repo, ms)) for ms in merge_scenarios]


def extract_conflicting_files(
    repo: git.Repo, merge_scenario: MergeScenario,
) -> List[Mapping[Revision, git.Blob]]:
    LOGGER.info(f"Extracting conflicting files for merge {merge_scenario.result.hexsha}")

    left = merge_scenario.left
    right = merge_scenario.right
    base = merge_scenario.base
    result = merge_scenario.result
    merge_idx: git.IndexFile = repo.index.from_tree(repo, base, left, right)

    left_result_diff = {
        diff.a_blob.hexsha: diff.b_blob for diff in left.diff(result) if diff.a_blob
    }
    right_result_diff = {
        diff.a_blob.hexsha: diff.b_blob for diff in right.diff(result) if diff.a_blob
    }
    base_result_diff = {
        diff.a_blob.hexsha: diff.b_blob for diff in base.diff(result) if diff.a_blob
    }

    file_merges = []

    for _, blobs in merge_idx.unmerged_blobs().items():
        rev_map = {}
        for stage, blob in blobs:
            if stage == 1:
                insert(blob, Revision.BASE, base_result_diff, rev_map)
            elif stage == 2:
                insert(blob, Revision.LEFT, left_result_diff, rev_map)
            elif stage == 3:
                insert(blob, Revision.RIGHT, right_result_diff, rev_map)
            else:
                raise ValueError("unknown stage " + stage)

        if rev_map[Revision.ACTUAL_MERGE] == None:
            LOGGER.warning("Could not find actual merge, skipping: " + str(rev_map))
            continue
        if Revision.LEFT not in rev_map or Revision.RIGHT not in rev_map:
            # this is a delete file/edit file conflict, we can't do much about that
            LOGGER.warning("Skipping delete/edit file conflict: " + str(rev_map))
            continue

        file_merges.append(rev_map)

    if not file_merges:
        LOGGER.info(f"No file merges required for merge commit {result.hexsha}")

    return file_merges


def insert(blob, rev, diff_map, rev_map):
    rev_map[rev] = blob
    if blob.hexsha in diff_map:
        result_blob = diff_map[blob.hexsha]
        assert (
            Revision.ACTUAL_MERGE not in rev_map
            or rev_map[Revision.ACTUAL_MERGE] == result_blob
        )
        rev_map[Revision.ACTUAL_MERGE] = result_blob


def clone_repo(
    repo_name: str, github_user: str, output_dir: Optional[pathlib.Path] = None
) -> git.Repo:
    """Clone a repo from GitHub and put it in
    'output_dir/github_user/repo_name', or just 'github_user/repo_name' if the
    output_dir is not specified.

    If the repo already exists locally, it is returned as-is, or copied to
    output_dir if specified.

    Args:
        repo_name: Name of the repository.
        github_user: Owner of the repository.
        output_dir: A directory to put the cloned repository in.
    Returns:
        A Git repo.
    """
    qualname = f"{github_user}/{repo_name}"

    output_dir = output_dir if output_dir is not None else pathlib.Path(os.getcwd())
    repo_path = output_dir / github_user / repo_name

    if not output_dir.exists():
        output_dir.mkdir(parents=True)

    if not repo_path.exists():
        url = f"https://github.com/{qualname}.git"
        repo = git.Repo.clone_from(url, str(repo_path))
    else:
        repo = git.Repo(str(repo_path))

    return repo
