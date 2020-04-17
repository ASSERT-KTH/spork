import subprocess
import sys
import time
import pathlib
import dataclasses
import collections
import enum
from typing import List, Generator, Iterable

import git
import daiquiri

from . import gitutils
from . import fileutils

LOGGER = daiquiri.getLogger(__name__)


class MergeOutcome(enum.Enum):
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


GitMergeResult = collections.namedtuple(
    "GitMergeResult",
    "merge_commit base_commit left_commit right_commit merge_ok build_ok",
)


def run_file_merges(
    file_merge_dirs: List[pathlib.Path], merge_cmd: str
) -> Iterable[MergeResult]:
    """Run the file merges in the provided directories and put the output in a file called `merge_cmd`.java.

    Args:
        file_merge_dirs: A list of directories with file merge scenarios. Each
            directory must contain Base.java, Left.java, Right.java and
            Expected.java
        merge_cmd: The merge command to execute. Will be called as
            `merge_cmd Left.java Base.java Right.java -o merge_cmd.java`.
    Returns:
        A generator that yields one MergeResult per merge directory.
    """
    # merge_cmd might be a path
    sanitized_merge_cmd = pathlib.Path(merge_cmd).name.replace(" ", "_")
    for merge_dir in file_merge_dirs:
        filenames = [f.name for f in merge_dir.iterdir() if f.is_file()]

        def get_filename(prefix: str) -> str:
            matches = [name for name in filenames if name.startswith(prefix)]
            assert len(matches) == 1
            return matches[0]

        merge_file = merge_dir / f"{sanitized_merge_cmd}.java"
        base = merge_dir / get_filename("Base")
        left = merge_dir / get_filename("Left")
        right = merge_dir / get_filename("Right")
        expected = merge_dir / get_filename("Expected")

        assert base.is_file()
        assert left.is_file()
        assert right.is_file()
        assert expected.is_file()

        outcome, runtime = run_file_merge(
            merge_dir,
            merge_cmd,
            base=base,
            left=left,
            right=right,
            expected=expected,
            merge=merge_file,
        )
        yield MergeResult(
            merge_dir=merge_dir,
            merge_file=merge_file,
            base_file=base,
            left_file=left,
            right_file=right,
            expected_file=expected,
            merge_cmd=sanitized_merge_cmd,
            outcome=outcome,
            runtime=runtime,
        )


def run_file_merge(scenario_dir, merge_cmd, base, left, right, expected, merge):
    start = time.perf_counter()
    merge_proc = subprocess.run(
        f"{merge_cmd} {left} {base} {right} -o {merge}".split(), capture_output=True,
    )
    runtime = time.perf_counter() - start

    if not merge.is_file():
        LOGGER.error(
            f"{merge_cmd} failed to produce a Merge.java file on {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        LOGGER.info(merge_proc.stdout.decode(sys.getdefaultencoding()))
        LOGGER.info(merge_proc.stderr.decode(sys.getdefaultencoding()))
        return MergeOutcome.FAIL, runtime
    elif merge_proc.returncode != 0:
        LOGGER.warning(
            f"Merge conflict in {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        return MergeOutcome.CONFLICT, runtime
    else:
        LOGGER.info(
            f"Successfully merged {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        return MergeOutcome.SUCCESS, runtime


def run_git_merges(
    merge_scenarios: List[gitutils.MergeScenario], repo: git.Repo, build: bool = False
) -> Iterable[GitMergeResult]:
    """Replay the provided merge scenarios using git-merge. Assumes that the
    merge scenarios belong to the provided repo. The merge tool to use must be
    configured in .gitattributes and .gitconfig, see the README at
    https://github.com/kth/spork for details on that.

    Args:
        merge_scenarios: A list of merge scenarios.
        repo: The related repository.
        build: If True, try to build the project with Maven after merge.
    Returns:
        An iterable of merge results.
    """
    for ms in merge_scenarios:
        LOGGER.info(f"Running scenario {ms.result.hexsha}")
        yield run_git_merge(ms, repo, build)


def run_git_merge(
    merge_scenario: gitutils.MergeScenario, repo: git.Repo, build: bool
) -> GitMergeResult:
    """Replay a single merge scenario. Assumes that the merge scenario belongs
    to the provided repo. The merge tool to use must be configured in
    .gitattributes and .gitconfig, see the README at
    https://github.com/kth/spork for details on that.

    Args:
        merge_scenario: A merge scenario.
        repo: The related repository.
        build: If True, try to build the project with Maven after merge.
    Returns:
        Result on the merge and potential build.
    """
    ms = merge_scenario # alias for less verbosity

    with gitutils.merge_no_commit(repo, ms.left.hexsha, ms.right.hexsha) as merge_ok:
        build_ok = fileutils.mvn_compile(workdir=repo.working_tree_dir) if build else False

    return GitMergeResult(
        merge_commit=ms.result.hexsha,
        merge_ok=merge_ok,
        build_ok=build_ok,
        base_commit=ms.base.hexsha,
        left_commit=ms.left.hexsha,
        right_commit=ms.right.hexsha,
    )

def is_buildable(commit_sha: str, repo: git.Repo) -> bool:
    """Try to build the commit with Maven.

    Args:
        commit_sha: A commit hexsha.
        repo: The related Git repo.
    Returns:
        True if the build was successful.
    """
    with gitutils.saved_git_head(repo):
        repo.git.switch(commit_sha, "--detach", "--quiet", "--force")
        return fileutils.mvn_compile(workdir=repo.working_tree_dir)
    

