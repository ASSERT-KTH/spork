import sys
import time
import tempfile
import collections
import dataclasses
import pathlib
import subprocess
import git
import argparse

from typing import List

import daiquiri
import logging


def setup_logging():
    daiquiri.setup(
        level=logging.INFO,
        outputs=(
            daiquiri.output.Stream(
                sys.stdout,
                formatter=daiquiri.formatter.ColorFormatter(
                    fmt="%(color)s[%(levelname)s] %(message)s%(color_stop)s"
                ),
            ),
            daiquiri.output.File(
                filename=str("spork_benchmark.log"),
                formatter=daiquiri.formatter.ColorFormatter(
                    fmt="%(asctime)s [PID %(process)d] [%(levelname)s] "
                    "%(name)s -> %(message)s"
                ),
            ),
        ),
    )


setup_logging()
LOGGER = daiquiri.getLogger(__name__)


@dataclasses.dataclass(frozen=True)
class MergeScenario:
    result: git.Commit
    base: git.Commit
    left: git.Commit
    right: git.Commit


def extract_merge_scenarios(repo: git.Repo):
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


def insert(revision, diffs: List[git.Commit], mapping):
    for d in diffs:
        base = d.a_blob
        rev = d.b_blob

        if not base or not rev:  # deletion or addition of a file, not interesting
            assert d.change_type in ["A", "D"]
            continue

        if base and base.name.endswith(".java") or rev and rev.name.endswith(".java"):
            mapping[d.a_blob.hexsha]["base"] = base
            mapping[d.a_blob.hexsha][revision] = rev


def create_merge_dirs(merge_dir_base, merge_scenarios):
    for merge in merge_scenarios:
        merge_dir = merge_dir_base / merge.result.hexsha
        merge_dir.mkdir()

        diff_left = merge.base.diff(merge.left)
        diff_right = merge.base.diff(merge.right)
        diff_result = merge.base.diff(merge.result)

        mapping = collections.defaultdict(dict)
        insert("left", diff_left, mapping)
        insert("right", diff_right, mapping)
        insert("result", diff_result, mapping)

        for key, val in mapping.items():
            scenario_dir = merge_dir / key
            scenario_dir.mkdir()

            base_content = val.get("base") or val.get("result")
            expected_content = val.get("result") or base_content

            write_blob_to_file(
                scenario_dir / "Left.java", val.get("left") or base_content
            )
            write_blob_to_file(
                scenario_dir / "Right.java", val.get("right") or base_content
            )
            write_blob_to_file(scenario_dir / "Base.java", base_content)
            write_blob_to_file(scenario_dir / "Expected.java", expected_content)


def write_blob_to_file(filepath, blob):
    filepath.write_bytes(blob.data_stream[-1].read())


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


def merge_files_separately(merge_scenarios, merge_cmd):
    num_merged = 0
    num_failed = 0

    start = time.time_ns()
    merge_base_dir = pathlib.Path("merge_directory")
    merge_base_dir.mkdir(exist_ok=True)

    create_merge_dirs(merge_base_dir, merge_scenarios)
    for merge_dir in merge_base_dir.iterdir():
        LOGGER.info(f"running merge scenarios for commit {merge_dir.name}")
        assert merge_dir.is_dir()

        for scenario_dir in merge_dir.iterdir():
            assert scenario_dir.is_dir()

            if run_merge(scenario_dir, merge_cmd):
                num_merged += 1
            else:
                num_failed += 1
        LOGGER.info(f"merged: {num_merged}, failed: {num_failed}")

    end = time.time_ns()
    delta = (end - start) / 1e9
    LOGGER.info(f"merged: {num_merged}, failed: {num_failed}")
    LOGGER.info(f"Time elapsed: {delta} seconds")
    LOGGER.info(f"Average time per merge: {delta / (num_merged + num_failed)}")


def run_git_merge(merge_scenarios: List[MergeScenario], repo: git.Repo):
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


def build_with_maven(repo: git.Repo) -> bool:
    proc = subprocess.run("mvn clean compile -U".split(), cwd=repo.working_dir)
    return proc.returncode == 0


def build_with_gradle(repo: git.Repo) -> bool:
    LOGGER.info("BUILDING WITH GRADLE")
    proc = subprocess.run(
        f"./gradlew clean build -x test -x testng".split(), cwd=repo.working_dir
    )
    return proc.returncode == 0


def create_cli_parser():
    parser = argparse.ArgumentParser(
        "Spork merge tester", description="A little program to help develop Spork!"
    )

    base_parser = argparse.ArgumentParser(add_help=False)

    base_parser.add_argument(
        "-r",
        "--repo",
        help="Name of the repo to run tests on. If -g is not specified, repo "
        "is assumed to be local.",
        type=str,
        required=True,
    )
    base_parser.add_argument(
        "-g",
        "--github-user",
        help="GitHub username to fetch repo from. Is combined with `--repo`"
        "to form a qualified repo name on the form `repo/user`. If this is "
        "not provided, the repo argument is assumend to be a local directory.",
        type=str,
    )
    base_parser.add_argument(
        "-n",
        "--num-merges",
        help="Maximum amount of merges to recreate.",
        type=int,
        default=100,
    )

    subparsers = parser.add_subparsers(dest="command")
    subparsers.required = True

    merge_command = subparsers.add_parser(
        "merge",
        help="Test a merge tool by merging one file at a time.",
        parents=[base_parser],
    )

    merge_command.add_argument(
        "--merge-cmd", help="Merge command.", type=str, required=True,
    )

    evaluate_command = subparsers.add_parser(
        "evaluate",
        help="Evaluate a merge tool by configuring git-merge to use it.",
        parents=[base_parser],
    )

    return parser


def main():
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.github_user is not None:
        qualname = f"{args.github_user}/{args.repo}"

        if not pathlib.Path(qualname).exists():
            url = f"https://github.com/{qualname}.git"
            repo = git.Repo.clone_from(url, qualname)
        else:
            repo = git.Repo(qualname)
    else:
        repo = git.Repo(args.repo)

    merge_scenarios = extract_merge_scenarios(repo)
    if args.num_merges > 0:
        merge_scenarios = merge_scenarios[: args.num_merges]

    LOGGER.info(f"recreating {len(merge_scenarios)} merges")

    if args.command == "merge":
        merge_files_separately(merge_scenarios, args.merge_cmd)
    elif args.command == "evaluate":
        run_git_merge(merge_scenarios, repo)
    else:
        raise ValueError(f"Unexpected command: {args.command}")


if __name__ == "__main__":
    main()
