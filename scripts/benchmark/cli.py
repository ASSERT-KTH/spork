"""The CLI for the benchmark suite."""
import sys
import pathlib
import git
import argparse
import functools
import collections
import itertools

from typing import List, Optional, Iterable

import daiquiri
import logging

from . import evaluate
from . import run
from . import gitutils
from . import fileutils
from . import reporter
from . import analyze
from . import mpi
from . import command
from . import containers as conts


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

    mpi_parser = argparse.ArgumentParser(add_help=False)
    mpi_parser.add_argument(
        "--mpi",
        help="Run merge in parallell using MPI"
        if mpi.MPI_ENABLED
        else argparse.SUPPRESS,
        action="store_true",
    )

    base_merge_parser = argparse.ArgumentParser(add_help=False, parents=[base_parser])
    base_merge_parser.add_argument(
        "--base-merge-dir",
        help="Base directory to perform the merges in.",
        type=pathlib.Path,
        default=pathlib.Path("merge_directory"),
    )
    base_merge_parser.add_argument(
        "--merge-commands",
        help="Merge commands to run.",
        type=str,
        required=True,
        nargs="+",
    )

    subparsers = parser.add_subparsers(dest="command")
    subparsers.required = True

    file_merge_command = subparsers.add_parser(
        "run-file-merges",
        help="Test a merge tool by merging one file at a time.",
        parents=[base_merge_parser, mpi_parser],
    )
    file_merge_command.add_argument(
        "--merge-commits",
        help="Path to a list of merge commit shas to operate on.",
        default=None,
        type=pathlib.Path,
    )
    file_merge_command.add_argument(
        "--gather-metainfo",
        help="Gather blob and file merge metainfo for all merge scenarios. Outputs to "
        "<OUTPUT_FILE_STEM>_blob_metainfo.csv and "
        "<OUTPUT_FILE_STEM>_file_merge_metainfo.csv.",
        action="store_true",
    )

    merge_and_compare_command = subparsers.add_parser(
        "run-file-merge-compare",
        help="Merge one file at a time, and compare the results to previous results. "
        "This is mostly useful in continuous integration.",
        parents=[base_merge_parser, mpi_parser],
    )
    merge_and_compare_command.add_argument(
        "--compare",
        help="Old results to compare against.",
        required=True,
        type=pathlib.Path,
    )

    git_merge_command = subparsers.add_parser(
        "run-git-merges",
        help="Replay the merge commits provided using Git and the currently configured merge driver.",
        parents=[base_parser],
    )
    git_merge_command.add_argument(
        "--merge-commits",
        help="Path to a list of merge commit shas to operate on.",
        required=True,
        type=pathlib.Path,
    )
    git_merge_command.add_argument(
        "--build",
        help="Try to build the project with Maven after the merge.",
        action="store_true",
    )

    runtime_bench_command = subparsers.add_parser(
        "runtime-benchmark",
        help="Benchmark runtime performance on a per-file basis for the provided merge commits.",
        description=(
            "Benchmark runtime performance on a per-file basis. Each merge "
            "command must output `Parse: ms`, `Merge: ms` and `Total: ms` "
            "as the final three lines of output."
        ),
        parents=[base_merge_parser],
    )
    runtime_bench_command.add_argument(
        "--file-merge-metainfo",
        help="Path to a CSV file with file merge metainfo. "
        "Use the `extract-file-merge-metainfo` command to generate one.",
        required=True,
        type=pathlib.Path,
    )
    runtime_bench_command.add_argument(
        "--num-runs",
        help="Amount of times to repeat each merge.",
        required=True,
        type=int,
    )

    merge_extractor_command = subparsers.add_parser(
        "extract-merge-commits",
        help="Extract merge commits from a repo.",
        parents=[base_parser],
    )
    merge_extractor_command.add_argument(
        "--non-trivial", help="Extract only non-trivial merges", action="store_true"
    )
    merge_extractor_command.add_argument(
        "--buildable",
        help="Only extract merge scenarios if they can be built with maven",
        action="store_true",
    )

    file_merge_metainfo_command = subparsers.add_parser(
        "extract-file-merge-metainfo",
        help="Extract metainfo for non-trivial file merges.",
        parents=[base_parser],
    )
    file_merge_metainfo_command.add_argument(
        "--merge-commits",
        help="Path to a list of merge commit shas to operate on.",
        default=None,
        type=pathlib.Path,
    )

    analyze_file_merges_command = subparsers.add_parser(
        "analyze-file-merges", help="Analyze results from the run-file-merges command.",
    )
    analyze_file_merges_command.add_argument(
        "--results",
        help="The primary results files.",
        required=True,
        type=pathlib.Path,
    )
    analyze_file_merges_command.add_argument(
        "--blob-metainfo",
        help="Blob metainfo for all blobs in the primary results file.",
        required=True,
        type=pathlib.Path,
    )
    analyze_file_merges_command.add_argument(
        "-o",
        "--output",
        help="Path to output file.",
        default="file_merge_analysis.csv",
        type=pathlib.Path,
    )

    return parser


def main():
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.command == "extract-merge-commits":
        command.extract_merge_commits(args)
        return
    elif args.command == "extract-file-merge-metainfo":
        command.extract_file_merge_metainfo(args)
        return
    elif args.command == "run-git-merges":
        command.git_merge(args)
        return
    elif args.command == "runtime-benchmark":
        command.runtime_benchmark(args)
        return
    elif args.command == "analyze-file-merges":
        command.analyze_file_merges(args)
        return

    eval_func = functools.partial(
        evaluate.run_and_evaluate,
        merge_commands=args.merge_commands,
        base_merge_dir=args.base_merge_dir,
    )

    if args.mpi and mpi.RANK != mpi.MASTER_RANK:
        mpi.worker(eval_func, len(args.merge_commands))
        return

    if args.command == "run-file-merges":
        command.run_file_merges(args, eval_func)
    elif args.command == "run-file-merge-compare":
        command.run_merge_and_compare(args, eval_func)
    else:
        raise ValueError(f"Unexpected command: {args.command}")


if __name__ == "__main__":
    main()
