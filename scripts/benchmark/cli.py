import sys
import pathlib
import git
import argparse

import daiquiri
import logging

from . import run
from . import gitutils
from . import mpi
from . import fileutils
from . import gather
from . import reporter


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

    subparsers = parser.add_subparsers(dest="command")
    subparsers.required = True

    merge_command = subparsers.add_parser(
        "merge",
        help="Test a merge tool by merging one file at a time.",
        parents=[base_parser],
    )

    merge_command.add_argument(
        "--merge-commands", help="Merge commands to run.", type=str, required=True, nargs="+"
    )
    merge_command.add_argument(
        "--mpi", help="Run merge in parallell using MPI", action="store_true",
    )

    evaluate_command = subparsers.add_parser(
        "evaluate",
        help="Evaluate a merge tool by configuring git-merge to use it.",
        parents=[base_parser],
    )

    return parser


def _merge(args: argparse.Namespace):
    if args.github_user is not None:
        repo = gitutils.clone_repo(args.repo, args.github_user)
    else:
        repo = git.Repo(args.repo)

    merge_scenarios = gitutils.extract_merge_scenarios(
        repo, args.num_merges if args.num_merges > 0 else None
    )

    LOGGER.info(f"recreating {len(merge_scenarios)} merges")

    merge_base_dir = pathlib.Path("merge_directory")
    merge_base_dir.mkdir(exist_ok=True)
    file_merges = gitutils.extract_all_conflicting_files(repo, merge_scenarios)
    merge_dirs = fileutils.create_merge_dirs(merge_base_dir, file_merges)

    LOGGER.info(f"Extracted {len(merge_dirs)} file merges")

    evaluations = gather.run_and_evaluate(merge_dirs, args.merge_commands, merge_base_dir)
    reporter.write_results(evaluations, "results.csv")


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


def _merge_mpi(args: argparse.Namespace):
    mpi.main(args)


def main():
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.command == "merge":
        if args.mpi:
            _merge_mpi(args)
        else:
            _merge(args)
    elif args.command == "evaluate":
        _evaluate(args)
    else:
        raise ValueError(f"Unexpected command: {args.command}")


if __name__ == "__main__":
    main()
