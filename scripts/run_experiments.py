"""Experiment module to evaluate Jav amerge tools."""
import pathlib
import sys
import functools
import random
import logging
import shutil

import numpy as np

import daiquiri

from benchmark import command
from benchmark import evaluate

NUM_SCENARIOS = 1000
MAX_SCENARIOS_PER_PROJECT = 50
MERGE_COMMANDS = ("spork", "jdime")
MERGE_DRIVERS = ("spork", "jdime")


def main():
    """Run the experiments."""
    setup_logging()

    projects = [
        proj.strip().split("/")
        for proj in pathlib.Path("buildable_candidates.txt")
        .read_text()
        .strip()
        .split("\n")
    ]

    random.shuffle(projects)
    merge_dirs = pathlib.Path("merge_dirs")

    total_num_merge_scenarios = 0

    for (github_user, repo_name) in projects:
        project_merge_scenarios = run_benchmarks_on_project(
            merge_dirs=merge_dirs,
            repo_name=repo_name,
            github_user=github_user,
            merge_scenario_limit=MAX_SCENARIOS_PER_PROJECT,
        )
        total_num_merge_scenarios += project_merge_scenarios
        if total_num_merge_scenarios >= NUM_SCENARIOS:
            break


def run_benchmarks_on_project(
    merge_dirs: pathlib.Path,
    repo_name: str,
    github_user: str,
    merge_scenario_limit: int,
) -> int:
    """Run the benchmarks on a single project.

    Args:
        merge_dirs: The base experiment directory to place output in.
        repo_name: Name of the repository.
        github_user: Name of the user/organization that owns the repository.
        merge_scenario_limit: The maximum amount of scenarios to benchmark from
            this project.
    Returns:
        The amount of merge scenarios that were used in the benchmark.
    """
    base_merge_dir = merge_dirs / f"{github_user}_{repo_name}"
    base_merge_dir.mkdir(exist_ok=True, parents=True)
    merge_scenarios_path = base_merge_dir / "merge_scenarios.csv"
    file_merge_output = base_merge_dir / "file_merge_results.csv"
    git_merge_output = base_merge_dir / "git_merge_results.csv"

    command.extract_merge_scenarios(
        repo_name=repo_name,
        github_user=github_user,
        non_trivial=True,
        buildable=True,
        testable=False,  # don't want to run tests
        skip_non_content_conflicts=False,  # implied by non_trivial, more eficient to disable
        output_file=merge_scenarios_path,
    )

    num_merge_scenarios = select_merge_scenarios(
        merge_scenarios_path, limit=merge_scenario_limit
    )

    if num_merge_scenarios == 0:
        shutil.rmtree(base_merge_dir)
        return 0

    eval_func = functools.partial(
        evaluate.run_and_evaluate,
        merge_commands=list(MERGE_COMMANDS),
        base_merge_dir=base_merge_dir,
    )

    command.run_file_merges(
        repo_name=repo_name,
        github_user=github_user,
        eval_func=eval_func,
        output_file=file_merge_output,
        use_mpi=False,
        merge_scenarios=merge_scenarios_path,
        num_merges=None,
        gather_metainfo=True,
        base_merge_dir=base_merge_dir,
    )

    command.git_merge(
        repo_name=repo_name,
        github_user=github_user,
        merge_drivers=list(MERGE_DRIVERS),
        merge_scenarios=merge_scenarios_path,
        build=True,
        base_eval_dir=base_merge_dir,
        num_merges=None,
        output_file=git_merge_output,
    )

    return num_merge_scenarios


def select_merge_scenarios(
    merge_scenarios_path: pathlib.Path, limit: int
) -> int:
    """Randomly select a maximum of limit merge scenarios and write them back
    into the file.

    If the amount of merge scenarios is already less than the limit, do nothing.

    Returns:
        the amount of merge scenarios
    """
    headers, *scenario_lines = [
        line
        for line in merge_scenarios_path.read_text(
            encoding=sys.getdefaultencoding()
        ).split("\n")
        if line.strip()
    ]
    num_scenarios = len(scenario_lines)
    if num_scenarios <= limit:
        return num_scenarios

    shutil.copy(
        merge_scenarios_path,
        merge_scenarios_path.parent / (merge_scenarios_path.name + ".bak"),
    )
    random_selection = np.random.choice(
        scenario_lines, size=limit, replace=False
    )
    lines = "\n".join([headers, *random_selection, ""])
    merge_scenarios_path.write_text(lines, encoding=sys.getdefaultencoding())
    return limit


def setup_logging():
    """Setup the logger to log both to stdout and to a file in the current
    working directory.
    """
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


if __name__ == "__main__":
    main()
