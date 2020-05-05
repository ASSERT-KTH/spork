"""Top-level commands that correspond to the CLI commands."""
import argparse
import pathlib
import sys
import itertools
import dataclasses
import re


from typing import List, Optional, Iterable, Mapping

import daiquiri
import git
import pandas

from . import evaluate
from . import run
from . import gitutils
from . import fileutils
from . import reporter
from . import analyze
from . import mpi
from . import containers as conts


LOGGER = daiquiri.getLogger(__name__)


def run_file_merges(args: argparse.Namespace, eval_func):
    """Run individual file merges."""
    commit_shas = (
        fileutils.read_non_empty_lines(args.merge_commits)
        if args.merge_commits
        else None
    )
    evaluations, file_merges, merge_dirs = _run_file_merges(
        args, eval_func, expected_merge_commit_shas=commit_shas
    )
    output_file = args.output or pathlib.Path("results.csv")
    reporter.write_csv(
        data=evaluations, container=conts.MergeEvaluation, dst=output_file,
    )

    if args.gather_metainfo:
        _output_java_blob_metainfos(merge_dirs, base_output_file=output_file)
        _output_file_merge_metainfos(file_merges, base_output_file=output_file)


def _output_java_blob_metainfos(merge_dirs, base_output_file):
    LOGGER.info("Gathering Java blob metainfo")
    metainfo_output_file = base_output_file.parent / (
        base_output_file.stem + "_blob_metainfo.csv"
    )
    metainfos = evaluate.gather_java_blob_metainfos(merge_dirs)
    reporter.write_csv(
        data=metainfos, container=conts.JavaBlobMetainfo, dst=metainfo_output_file,
    )
    LOGGER.info(f"Java blob metainfo written to {metainfo_output_file}")


def _output_file_merge_metainfos(file_merges, base_output_file):
    LOGGER.info("Gathering file merge metainfo")
    metainfo_output_file = base_output_file.parent / (
        base_output_file.stem + "_file_merge_metainfo.csv"
    )
    file_merge_metainfos = list(
        map(conts.FileMergeMetainfo.from_file_merge, file_merges)
    )
    reporter.write_csv(
        data=file_merge_metainfos,
        container=conts.FileMergeMetainfo,
        dst=metainfo_output_file,
    )
    LOGGER.info(f"File merge metainfo written to {metainfo_output_file}")


def run_merge_and_compare(args: argparse.Namespace, eval_func):
    """Run individual file merges and compare the results to previous results."""
    old_evaluations = analyze.Evaluations.from_path(
        args.compare, container=conts.MergeEvaluation
    )
    commit_shas = [path for path in old_evaluations.extract("merge_commit")]
    data, _, _ = _run_file_merges(
        args, eval_func, expected_merge_commit_shas=commit_shas
    )
    new_evaluations = analyze.Evaluations(data=data, container=conts.MergeEvaluation,)

    new_evaluations.log_diffs(old_evaluations)

    if args.output is not None:
        reporter.write_csv(
            data=new_evaluations.data, container=conts.MergeEvaluation, dst=args.output
        )

    if new_evaluations.at_least_as_good_as(old_evaluations):
        LOGGER.info("New results were no worse than the reference")
        sys.exit(0)
    else:
        LOGGER.warning("New results were worse than the reference")
        sys.exit(1)


def extract_merge_commits(args: argparse.Namespace):
    """Extract merge commits."""
    repo = _get_repo(args.repo, args.github_user)

    merge_scenarios = gitutils.extract_merge_scenarios(
        repo, non_trivial=args.non_trivial
    )

    if args.buildable or args.testable:
        buildable = [
            ms
            for ms in merge_scenarios
            if all(
                run.is_buildable(commit.hexsha, repo)
                for commit in [ms.base, ms.left, ms.right, ms.expected]
            )
        ]
        LOGGER.info(
            f"Filtered {len(merge_scenarios) - len(buildable)} merges that did not build"
        )
        merge_scenarios = buildable
        if args.testable:
            testable = [
                ms
                for ms in merge_scenarios
                if run.is_testable(ms.expected.hexsha, repo)
            ]
            LOGGER.info(
                f"Filtered {len(merge_scenarios) - len(testable)} merges that could not be tested"
            )
            merge_scenarios = testable

    LOGGER.info(f"Extracted {len(merge_scenarios)} merge commits")

    outpath = args.output or pathlib.Path("merge_scenarios.txt")
    outpath.write_text("\n".join([merge.expected.hexsha for merge in merge_scenarios]))
    LOGGER.info(f"Merge commits saved to {outpath}")


def extract_file_merge_metainfo(args: argparse.Namespace):
    """Extract metainfo about the file merges."""
    repo = _get_repo(args.repo, args.github_user)
    commit_shas = (
        fileutils.read_non_empty_lines(args.merge_commits)
        if args.merge_commits
        else None
    )

    merge_scenarios = gitutils.extract_merge_scenarios(
        repo, merge_commit_shas=commit_shas
    )
    file_merges = gitutils.extract_all_conflicting_files(repo, merge_scenarios)
    file_merge_metainfos = list(
        map(conts.FileMergeMetainfo.from_file_merge, file_merges)
    )[: args.num_merges]
    reporter.write_csv(
        data=file_merge_metainfos,
        container=conts.FileMergeMetainfo,
        dst=args.output or "file_merge_metainfo.csv",
    )


def git_merge(args: argparse.Namespace):
    """Run git merge on all scenarios."""
    repo = _get_repo(args.repo, args.github_user)

    commit_shas = fileutils.read_non_empty_lines(args.merge_commits)[: args.num_merges]
    merge_scenarios = gitutils.extract_merge_scenarios(
        repo, merge_commit_shas=commit_shas
    )
    merge_results = run.run_git_merges(
        merge_scenarios, args.merge_drivers, repo, args.build, args.evaluate
    )
    reporter.write_csv(
        data=merge_results, container=conts.GitMergeResult, dst=args.output
    )


def runtime_benchmark(args: argparse.Namespace):
    """Run a runtime benchmark on individual file merges."""
    repo = _get_repo(args.repo, args.github_user)
    file_merge_metainfo = reporter.read_csv(
        csv_file=args.file_merge_metainfo, container=conts.FileMergeMetainfo
    )
    file_merges = (conts.FileMerge.from_metainfo(repo, m) for m in file_merge_metainfo)
    merge_dirs = fileutils.create_merge_dirs(args.base_merge_dir, file_merges)[
        : args.num_merges
    ]

    runtime_results = itertools.chain.from_iterable(
        run.runtime_benchmark(merge_dirs, merge_cmd, args.num_runs)
        for merge_cmd in args.merge_commands
    )
    reporter.write_csv(
        data=runtime_results, container=conts.RuntimeResult, dst=args.output
    )


def analyze_file_merges(args: argparse.Namespace):
    """Analyze results from running file merges."""

    def _to_dataframe(data, container):
        headers = [f.name for f in dataclasses.fields(container)]
        tuples = map(dataclasses.astuple, data)
        return pandas.DataFrame(data=tuples, columns=headers)

    git_diffs = []

    for results_file in args.results:
        project = results_file.name.split("_")[0]
        blob_metainfo_file = results_file.parent / (
            results_file.stem + "_blob_metainfo.csv"
        )

        # read into dataclasses first to ensure type correctness
        merge_evaluations = _to_dataframe(
            reporter.read_csv(results_file, container=conts.MergeEvaluation),
            container=conts.MergeEvaluation,
        )

        blob_metainfo = reporter.read_csv(
            container=conts.JavaBlobMetainfo, csv_file=blob_metainfo_file
        )

        blob_line_counts = {
            blob_meta.hexsha: blob_meta.num_lines for blob_meta in blob_metainfo
        }
        git_diff = analyze.analyze_merge_evaluations(
            merge_evaluations, project, blob_line_counts
        )
        git_diffs.append(git_diff)

    titled_frames = zip(["Git diff {}",], [git_diffs],)

    for title, frames in titled_frames:
        concat = pandas.concat(frames).sort_values(by="merge_cmd")
        _print_latex_table(
            title.format("accuracy"),
            concat[["project", "merge_cmd", "acc_mean"]].rename(
                columns={"acc_mean": "mean"}
            ),
        )
        _print_latex_table(
            title.format("magnitude"),
            concat[["project", "merge_cmd", "magn_mean"]].rename(
                columns={"magn_mean": "mean"}
            ),
        )


def _print_latex_table(title, concat):
    projects = {project for project in concat.project}

    result_frame = pandas.DataFrame(columns=["project"], data=sorted(projects))

    for merge_cmd, df in concat.groupby("merge_cmd"):
        result_frame = result_frame.merge(
            df.rename(columns={"mean": merge_cmd})[["project", merge_cmd]], on="project"
        )

    means = result_frame.mean()
    means["project"] = "Total"
    result_frame.loc["mean"] = means

    result_frame.reset_index()
    safe_title = re.sub("[^a-z_]", "", title.replace(" ", "_").lower())
    latex = result_frame.to_latex(
        float_format="%.3f", caption=title, label=f"tab:res:{safe_title}", index=False,
    )

    filename = f"{safe_title}.tex"
    pathlib.Path(
        f"{safe_title}.tex", mode="w", encoding=sys.getdefaultencoding()
    ).write_text(latex)
    LOGGER.info(f"Wrote table to {filename}")


def _print_latex_tables(titled_frames):
    """Print one file with latex tables per merge command."""
    files = {}
    for title, frames in titled_frames:
        concat = pandas.concat(frames)
        for merge_cmd, df in concat.groupby("merge_cmd"):
            if merge_cmd not in files:
                files[merge_cmd] = open(
                    merge_cmd + ".tex", mode="a", encoding=sys.getdefaultencoding()
                )

            df_without_cmd = df.loc[:, df.columns != "merge_cmd"]
            file = files[merge_cmd]
            latex = df_without_cmd.to_latex(
                float_format="%.3f",
                caption=f"{merge_cmd.split('_')[0].capitalize()} {title}".replace(
                    "_", " "
                ),
                label=f"tab:res:{merge_cmd}_{title}".replace(" ", "_").lower(),
                index=False,
            )
            files[merge_cmd].write(latex)

    for file in files.values():
        file.close()


def _run_file_merges(
    args: argparse.Namespace,
    eval_func,
    expected_merge_commit_shas: Optional[List[str]],
) -> (Iterable[conts.MergeEvaluation], List[conts.FileMerge]):
    assert not args.mpi or mpi.RANK == mpi.MASTER_RANK

    repo = _get_repo(args.repo, args.github_user)

    merge_scenarios = gitutils.extract_merge_scenarios(repo, expected_merge_commit_shas)

    LOGGER.info(f"Found {len(merge_scenarios)} merge scenarios")

    merge_base_dir = pathlib.Path("merge_directory")
    merge_base_dir.mkdir(parents=True, exist_ok=True)
    file_merges = list(gitutils.extract_all_conflicting_files(repo, merge_scenarios))[
        : args.num_merges
    ]
    merge_dirs = fileutils.create_merge_dirs(merge_base_dir, file_merges)

    LOGGER.info(f"Extracted {len(merge_dirs)} file merges")

    if args.mpi:
        evaluations = mpi.master(merge_dirs)
    else:
        evaluations = eval_func(merge_dirs)

    return evaluations, file_merges, merge_dirs


def _get_repo(repo: str, github_user: Optional[str]) -> git.Repo:
    if github_user is not None:
        return gitutils.clone_repo(repo, github_user)
    else:
        return git.Repo(repo)
