import subprocess
import os
import sys
import time
import pathlib
import dataclasses
import collections
import enum
from typing import List, Generator, Iterable, Tuple

import git
import daiquiri

from . import gitutils
from . import fileutils
from . import containers as conts

LOGGER = daiquiri.getLogger(__name__)


def run_file_merges(
    file_merge_dirs: List[pathlib.Path], merge_cmd: str
) -> Iterable[conts.MergeResult]:
    """Run the file merges in the provided directories and put the output in a file called `merge_cmd`.java.

    Args:
        file_merge_dirs: A list of directories with file merge scenarios. Each
            directory must contain Base.java, Left.java, Right.java and
            Expected.java
        merge_cmd: The merge command to execute. Will be called as
            `merge_cmd Left.java Base.java Right.java -o merge_cmd.java`.
    Returns:
        A generator that yields one conts.MergeResult per merge directory.
    """
    for merge_result, _ in _run_file_merges(file_merge_dirs, merge_cmd):
        yield merge_result


def _run_file_merges(file_merge_dirs: List[pathlib.Path], merge_cmd: str) -> Iterable:
    sanitized_merge_cmd = pathlib.Path(merge_cmd).name.replace(" ", "_")
    for merge_dir in file_merge_dirs:
        filenames = [f.name for f in merge_dir.iterdir() if f.is_file()]

        def get_filename(prefix: str) -> str:
            matches = [
                name
                for name in filenames
                if name.startswith(prefix)
                and not name.endswith(fileutils.NORMALIZED_FILE_SUFFIX)
            ]
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

        outcome, runtime, proc = _run_file_merge(
            merge_dir,
            merge_cmd,
            base=base,
            left=left,
            right=right,
            expected=expected,
            merge=merge_file,
        )
        yield conts.MergeResult(
            merge_dir=merge_dir,
            merge_file=merge_file,
            base_file=base,
            left_file=left,
            right_file=right,
            expected_file=expected,
            merge_cmd=sanitized_merge_cmd,
            outcome=outcome,
            runtime=runtime,
        ), proc


def _run_file_merge(scenario_dir, merge_cmd, base, left, right, expected, merge):
    start = time.perf_counter()
    proc = subprocess.run(
        f"{merge_cmd} {left} {base} {right} -o {merge}".split(), capture_output=True,
    )
    runtime = time.perf_counter() - start

    if not merge.is_file():
        LOGGER.error(
            f"{merge_cmd} failed to produce a merge file on {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        LOGGER.info(proc.stdout.decode(sys.getdefaultencoding()))
        LOGGER.info(proc.stderr.decode(sys.getdefaultencoding()))
        return conts.MergeOutcome.FAIL, runtime, proc
    elif proc.returncode != 0:
        LOGGER.warning(
            f"Merge conflict in {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        return conts.MergeOutcome.CONFLICT, runtime, proc
    else:
        LOGGER.info(
            f"Successfully merged {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        return conts.MergeOutcome.SUCCESS, runtime, proc


def run_git_merges(
    merge_scenarios: List[conts.MergeScenario], repo: git.Repo, build: bool = False
) -> Iterable[conts.GitMergeResult]:
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
        LOGGER.info(f"Running scenario {ms.expected.hexsha}")
        yield run_git_merge(ms, repo, build)


def run_git_merge(
    merge_scenario: conts.MergeScenario, repo: git.Repo, build: bool
) -> conts.GitMergeResult:
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
    ms = merge_scenario  # alias for less verbosity

    with gitutils.merge_no_commit(repo, ms.left.hexsha, ms.right.hexsha) as merge_ok:
        build_ok = (
            fileutils.mvn_compile(workdir=repo.working_tree_dir) if build else False
        )

    return conts.GitMergeResult(
        merge_commit=ms.expected.hexsha,
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


def runtime_benchmark(
    file_merge_dirs: List[pathlib.Path], merge_cmd: str, repeats: int
) -> Iterable[conts.RuntimeResult]:
    for _ in range(repeats):
        for ms, proc in _run_file_merges(file_merge_dirs, merge_cmd):
            assert ms.outcome != conts.MergeOutcome.FAIL
            parse_time, merge_time, total_time = _parse_runtimes(proc.stdout)

            merge_commit = fileutils.extract_commit_sha(ms.merge_dir)
            base_blob, left_blob, right_blob = [
                fileutils.extract_blob_sha(fp)
                for fp in [ms.base_file, ms.left_file, ms.right_file]
            ]

            yield conts.RuntimeResult(
                merge_commit=merge_commit,
                base_blob=base_blob,
                left_blob=left_blob,
                right_blob=right_blob,
                merge_cmd=merge_cmd,
                parse_time_ms=parse_time,
                merge_time_ms=merge_time,
                total_time_ms=total_time,
            )


def _parse_runtimes(stdout: bytes) -> Tuple[float]:
    decoded = stdout.decode(sys.getdefaultencoding())
    parse_line, merge_line, total_line = decoded.strip().split("\n")[-3:]

    return (
        _parse_runtime(parse_line, "Parse:"),
        _parse_runtime(merge_line, "Merge:"),
        _parse_runtime(total_line, "Total:"),
    )


def _parse_runtime(line: str, expected_prefix: str) -> float:
    line = line.strip()
    if not line.startswith(expected_prefix):
        raise RuntimeError(f"Expected line to start with {expected_prefix}: {line}")

    return float(line.split()[-1])
