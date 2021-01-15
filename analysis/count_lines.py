import pathlib
import tempfile
import os
import dataclasses

import git
import github
import pandas as pd

from benchmark.gitutils import num_core_contributors

gh = github.Github(login_or_token=os.getenv("GITHUB_OAUTH_TOKEN"))


@dataclasses.dataclass(frozen=True)
class ProjectStats:
    reaper_dataset_name: str
    current_name: str
    url: str
    num_merge_scenarios: int
    num_file_merges: int
    cloc: int
    num_stars: int
    core_contribs: int


def count_lines(file) -> int:
    encodings = ["utf8", "latin1"]
    for encoding in encodings:
        try:
            return len(file.read_text(encoding=encoding).split("\n"))
        except ValueError:
            pass
    raise ValueError(f"can't decode {file}")


def count_java_lines(rootdir: pathlib.Path) -> int:
    return sum(count_lines(file) for file in rootdir.rglob("*.java"))


def get_stats(proj: str, file_merges: pd.DataFrame) -> ProjectStats:
    gh_repo = gh.get_repo(proj)
    num_stars = gh_repo.stargazers_count

    with tempfile.TemporaryDirectory() as tmpdir:
        git_repo = git.Repo.clone_from(gh_repo.clone_url, tmpdir)
        cloc = count_java_lines(pathlib.Path(git_repo.working_tree_dir))
        core_contribs = num_core_contributors(repo=git_repo, threshold=0.8)
        num_file_merges = len(
            file_merges[file_merges.project == proj].merge_dir.unique()
        )
        num_merge_scenarios = len(
            file_merges[file_merges.project == proj].merge_commit.unique()
        )

    return ProjectStats(
        reaper_dataset_name=proj,
        current_name=gh_repo.full_name,
        url=gh_repo.html_url,
        num_merge_scenarios=num_merge_scenarios,
        num_file_merges=num_file_merges,
        cloc=cloc,
        num_stars=num_stars,
        core_contribs=core_contribs,
    )


def get_stats_data_frame(
    file_merges: pd.DataFrame
) -> pd.DataFrame:
    projects = sorted(file_merges.project.unique())
    stats_frame = pd.DataFrame(
        columns=[f.name for f in dataclasses.fields(ProjectStats)]
    )
    for i, proj in enumerate(projects):
        stats = get_stats(proj, file_merges)
        stats_frame.loc[i] = dataclasses.astuple(stats)

    return stats_frame
