import sys
import pathlib
import git
import argparse
import functools

from typing import List

import daiquiri
import logging

from mpi4py import MPI

from . import run
from . import gitutils
from . import mpi
from . import fileutils
from . import gather
from . import reporter
from . import analyze


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
            # daiquiri.output.File(
            #    filename=str("spork_benchmark.log"),
            #    formatter=daiquiri.formatter.ColorFormatter(
            #        fmt="%(asctime)s [PID %(process)d] [%(levelname)s] "
            #        "%(name)s -> %(message)s"
            #    ),
            # ),
        ),
    )


setup_logging()
LOGGER = daiquiri.getLogger(__name__)


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
    base_parser.add_argument(
        "--merge-commands",
        help="Merge commands to run.",
        type=str,
        required=True,
        nargs="+",
    )
    base_parser.add_argument(
        "--base-merge-dir",
        help="Base directory to perform the merges in.",
        type=pathlib.Path,
        default=pathlib.Path("merge_directory"),
    )
    base_parser.add_argument(
        "--mpi", help="Run merge in parallell using MPI", action="store_true",
    )
    subparsers = parser.add_subparsers(dest="command")
    subparsers.required = True

    merge_command = subparsers.add_parser(
        "merge",
        help="Test a merge tool by merging one file at a time.",
        parents=[base_parser],
    )

    merge_and_compare_command = subparsers.add_parser(
        "merge-compare",
        help="Merge like with the merge command, and compare the results to "
        "some previous results.",
        parents=[base_parser],
    )
    merge_and_compare_command.add_argument(
        "--compare",
        help="Old results to compare against.",
        required=True,
        type=pathlib.Path,
    )

    return parser


def _run_merges(args: argparse.Namespace) -> List[gather.MergeEvaluation]:
    evaluation_function = functools.partial(
        gather.run_and_evaluate,
        merge_commands=args.merge_commands,
        base_merge_dir=args.base_merge_dir,
    )
    if args.mpi and MPI.COMM_WORLD.Get_rank() != mpi.MASTER_RANK:
        mpi.worker(evaluation_function, len(args.merge_commands))
        return None

    if args.github_user is not None:
        repo = gitutils.clone_repo(args.repo, args.github_user)
    else:
        repo = git.Repo(args.repo)

    merge_scenarios = gitutils.extract_merge_scenarios(
        repo, args.num_merges if args.num_merges > 0 else None
    )

    LOGGER.info(f"recreating {len(merge_scenarios)} merges")

    merge_base_dir = pathlib.Path("merge_directory")
    merge_base_dir.mkdir(parents=True, exist_ok=True)
    file_merges = gitutils.extract_all_conflicting_files(repo, merge_scenarios)
    merge_dirs = fileutils.create_merge_dirs(merge_base_dir, file_merges)

    LOGGER.info(f"Extracted {len(merge_dirs)} file merges")

    if args.mpi:
        evaluations = mpi.master(merge_dirs)
    else:
        evaluations = evaluation_function(merge_dirs)

    return evaluations


def _merge(args: argparse.Namespace):
    evaluations = _run_merges(args)

    if not evaluations:
        assert args.mpi and MPI.COMM_WORLD.Get_rank() != mpi.MASTER_RANK
        return

    reporter.write_results(evaluations, "results.csv")


def _merge_and_compare(args: argparse.Namespace):
    old_evaluations = analyze.Evaluations.from_path(args.compare)
    evaluations = _run_merges(args)

    if not evaluations:
        assert args.mpi and MPI.COMM_WORLD.Get_rank() != mpi.MASTER_RANK
        return

    new_evaluations = analyze.Evaluations(evaluations)
    new_evaluations.log_diffs(old_evaluations)

    if new_evaluations.at_least_as_good_as(old_evaluations):
        LOGGER.info("New results were no worse than the reference")
        sys.exit(0)
    else:
        LOGGER.warning("New results were worse than the reference")
        sys.exit(1)


def _evaluate(args: argparse.Namespace):
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.github_user is not None:
        repo = gitutils.clone_repo(args.repo, args.github_user)
    else:
        repo = git.Repo(args.repo)

    merge_scenarios = gitutils.extract_merge_scenarios(
        repo, args.num_merges if args.num_merges > 0 else None
    )

    LOGGER.info(f"recreating {len(merge_scenarios)} merges")

    run.run_git_merge(merge_scenarios, repo)


def main():
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.command == "merge":
        _merge(args)
    elif args.command == "merge-compare":
        _merge_and_compare(args)
    else:
        raise ValueError(f"Unexpected command: {args.command}")


if __name__ == "__main__":
    main()
