import subprocess
import sys
import time
import pathlib
import dataclasses
from typing import List, Generator

import git
import daiquiri

from . import gitutils

LOGGER = daiquiri.getLogger(__name__)


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


def run_file_merges(
    file_merge_dirs: List[pathlib.Path], merge_cmd: str
) -> Generator[MergeResult, None, None]:
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


def merge_files_separately(merge_dirs, merge_cmd):
    num_merged = 0
    num_failed = 0

    start = time.time_ns()
    for merge_dir in merge_dirs:
        LOGGER.info(
            f"running merge scenarios for merge dir {merge_dir.parent.name}/{merge_dir.name}"
        )
        assert merge_dir.is_dir()

        if run_file_merge(merge_dir, merge_cmd):
            num_merged += 1
        else:
            num_failed += 1

        LOGGER.info(f"merged: {num_merged}, failed: {num_failed}")

    end = time.time_ns()
    delta = (end - start) / 1e9
    LOGGER.info(f"merged: {num_merged}, failed: {num_failed}")
    LOGGER.info(f"Time elapsed: {delta} seconds")
    LOGGER.info(f"Average time per merge: {delta / (num_merged + num_failed)}")


def run_git_merge(merge_scenarios: List[gitutils.MergeScenario], repo: git.Repo):
    passed_merge = 0
    failed_merge = 0

    passed_build = 0
    failed_build = 0

    for mc in merge_scenarios:
        LOGGER.info(f"MERGING {mc}\n\n")
        repo.git.checkout(mc.left.hexsha, "-f")

        try:
            out = repo.git.merge(mc.right.hexsha)
            LOGGER.info("MERGE OK")
            passed_merge += 1

        except git.GitCommandError as exc:
            LOGGER.info(exc)
            failed_merge += 1

    LOGGER.info(f"passed_merge: {passed_merge}, failed_merge: {failed_merge}")
    LOGGER.info(f"passed_build: {passed_build}, failed_build: {failed_build}")
