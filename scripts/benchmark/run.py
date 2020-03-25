import subprocess
import sys
import time
from typing import List

import git
import daiquiri

from . import fileutils
from . import gitutils

LOGGER = daiquiri.getLogger(__name__)


def run_merge(scenario_dir, merge_cmd):
    base = scenario_dir / "Base.java"
    left = scenario_dir / "Left.java"
    right = scenario_dir / "Right.java"
    expected = scenario_dir / "Expected.java"
    merge = scenario_dir / "Merge.java"

    assert base.is_file()
    assert left.is_file()
    assert right.is_file()
    assert expected.is_file()

    merge_proc = subprocess.run(
        f"{merge_cmd} {left} {base} {right} -o {merge}".split(), capture_output=True,
    )

    if not merge.is_file():
        LOGGER.error(
            f"merge failed to produce a Merge.java file on {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        LOGGER.info(merge_proc.stdout.decode(sys.getdefaultencoding()))
        LOGGER.info(merge_proc.stderr.decode(sys.getdefaultencoding()))
        return False
    else:
        LOGGER.info(
            f"Successfully merged {scenario_dir.parent.name}/{scenario_dir.name}"
        )
        return True


def merge_files_separately(merge_dirs, merge_cmd):
    num_merged = 0
    num_failed = 0

    start = time.time_ns()
    for merge_dir in merge_dirs:
        LOGGER.info(f"running merge scenarios for merge dir {merge_dir.parent.name}/{merge_dir.name}")
        assert merge_dir.is_dir()

        if run_merge(merge_dir, merge_cmd):
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
