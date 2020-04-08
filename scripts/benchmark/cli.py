import sys
import pathlib
import git
import argparse
import functools

from typing import List, Optional, Iterable

import daiquiri
import logging

from . import run
from . import gitutils
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

MPI_ENABLED = False
try:
    from mpi4py import MPI
    from . import mpi

    MPI_ENABLED = True
except ModuleNotFoundError:
    LOGGER.warning("MPI not installed, will not be able to run in MPI-mode")


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
        "--mpi",
        help="Run merge in parallell using MPI" if MPI_ENABLED else argparse.SUPPRESS,
        action="store_true",
    )
    base_parser.add_argument(
        "-n",
        "--num-merges",
        help="Maximum amount of file merges to recreate.",
        type=int,
        default=None,
    )
    base_parser.add_argument(
        "-o",
        "--output",
        help="Where to store the output.",
        type=pathlib.Path,
        default=None,
    )

    subparsers = parser.add_subparsers(dest="command")
    subparsers.required = True

    merge_command = subparsers.add_parser(
        "merge",
        help="Test a merge tool by merging one file at a time.",
        parents=[base_parser],
    )
    merge_command.add_argument(
        "--merge-commits",
        help="Path to a list of merge commit shas to operate on.",
        default=None,
        type=pathlib.Path,
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


def _run_merges(
    args: argparse.Namespace,
    eval_func,
    expected_merge_commit_shas: Optional[List[str]],
) -> Iterable[gather.MergeEvaluation]:
    assert not args.mpi or MPI.COMM_WORLD.Get_rank() == mpi.MASTER_RANK

    if args.github_user is not None:
        repo = gitutils.clone_repo(args.repo, args.github_user)
    else:
        repo = git.Repo(args.repo)

    merge_scenarios = gitutils.extract_merge_scenarios(repo, expected_merge_commit_shas)

    LOGGER.info(f"Found {len(merge_scenarios)} merge scenarios")

    merge_base_dir = pathlib.Path("merge_directory")
    merge_base_dir.mkdir(parents=True, exist_ok=True)
    file_merges = gitutils.extract_all_conflicting_files(repo, merge_scenarios)
    merge_dirs = fileutils.create_merge_dirs(merge_base_dir, file_merges)[
        : args.num_merges
    ]

    LOGGER.info(f"Extracted {len(merge_dirs)} file merges")

    if args.mpi:
        evaluations = mpi.master(merge_dirs)
    else:
        evaluations = eval_func(merge_dirs)

    return evaluations


def _merge(args: argparse.Namespace, eval_func):
    commit_shas = None
    if args.merge_commits:
        commit_shas = [
            line.strip()
            for line in args.merge_commits.read_text(
                encoding=sys.getdefaultencoding()
            ).split("\n")
            if line.strip()
        ]

    evaluations = _run_merges(args, eval_func, expected_merge_commit_shas=commit_shas)
    reporter.write_results(evaluations, args.output or "results.csv")


def _merge_and_compare(args: argparse.Namespace, eval_func):
    old_evaluations = analyze.Evaluations.from_path(args.compare)
    commit_shas = [
        list(pathlib.Path(path).parents)[-2].name
        for path in old_evaluations.extract(gather.EvalAttrName.merge_dir.value)
    ]
    new_evaluations = analyze.Evaluations(
        _run_merges(args, eval_func, expected_merge_commit_shas=commit_shas)
    )

    new_evaluations.log_diffs(old_evaluations)

    if args.output is not None:
        reporter.write_results(new_evaluations.evaluations, args.output)

    if new_evaluations.at_least_as_good_as(old_evaluations):
        LOGGER.info("New results were no worse than the reference")
        sys.exit(0)
    else:
        LOGGER.warning("New results were worse than the reference")
        sys.exit(1)


def _evaluate(args: argparse.Namespace):
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.mpi and not MPI_ENABLED:
        LOGGER.error("--mpi specified, but mpi4py is not installed")

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

    eval_func = functools.partial(
        gather.run_and_evaluate,
        merge_commands=args.merge_commands,
        base_merge_dir=args.base_merge_dir,
    )

    if args.mpi and MPI.COMM_WORLD.Get_rank() != mpi.MASTER_RANK:
        mpi.worker(eval_func, len(args.merge_commands))
        return

    if args.command == "merge":
        _merge(args, eval_func)
    elif args.command == "merge-compare":
        _merge_and_compare(args, eval_func)
    else:
        raise ValueError(f"Unexpected command: {args.command}")


if __name__ == "__main__":
    main()
