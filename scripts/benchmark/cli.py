import sys
import pathlib
import git
import argparse

import daiquiri
import logging

from . import run
from . import gitutils


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

    merge_scenarios = gitutils.extract_merge_scenarios(repo)
    if args.num_merges > 0:
        merge_scenarios = merge_scenarios[: args.num_merges]

    LOGGER.info(f"recreating {len(merge_scenarios)} merges")

    merge_base_dir = pathlib.Path("merge_directory")

    if args.command == "merge":
        run.merge_files_separately(merge_base_dir, merge_scenarios, args.merge_cmd)
    elif args.command == "evaluate":
        run.run_git_merge(merge_scenarios, repo)
    else:
        raise ValueError(f"Unexpected command: {args.command}")


if __name__ == "__main__":
    main()
