"""Module with some Git utility functions and classes."""
import sys
import dataclasses
import os
import pathlib
import shutil
from typing import List, Optional

import git
import daiquiri

LOGGER = daiquiri.getLogger(__name__)


@dataclasses.dataclass(frozen=True)
class MergeScenario:
    result: git.Commit
    base: git.Commit
    left: git.Commit
    right: git.Commit


def extract_merge_scenarios(repo: git.Repo, limit: Optional[int] = None) -> List[MergeScenario]:
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
