"""Module with some Git utility functions and classes."""
import dataclasses
from typing import List

import git
import daiquiri

LOGGER = daiquiri.getLogger(__name__)


@dataclasses.dataclass(frozen=True)
class MergeScenario:
    result: git.Commit
    base: git.Commit
    left: git.Commit
    right: git.Commit


def extract_merge_scenarios(repo: git.Repo) -> List[MergeScenario]:
    """Extract all merge scenarios from the repo."""
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

    return merge_scenarios
