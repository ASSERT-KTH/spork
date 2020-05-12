"""Module with some Git utility functions and classes."""
import sys
import enum
import dataclasses
import os
import itertools
import pathlib
import shutil
import contextlib
import subprocess
import re

from typing import (
    List,
    Optional,
    Mapping,
    Sequence,
    Tuple,
    Iterable,
    ContextManager,
)

import git
import daiquiri

from . import containers as conts

START_CONFLICT = "<<<<<<<"
MID_CONFLICT = "======="
END_CONFLICT = ">>>>>>>"

LOGGER = daiquiri.getLogger(__name__)

FILE_MERGE_LOCATOR_DRIVER_CONFIG = ("filemergelocator", "*.java")
FILE_MERGE_LOCATOR_OUTPUT_NAME = ".filemergelocator_results"

CONFLICT_PATTERN = re.compile("(?m)^CONFLICT \((.*?)\):")


def extract_merge_scenarios(
    repo: git.Repo,
    non_trivial: bool = False,
    merge_commit_shas: Optional[List[str]] = None,
) -> List[conts.MergeScenario]:
    """Extract merge scenarios from a repo.

    Args:
        repo: A Git repo.
        merge_commit_shas: Commit shas to extract scenarios for.
        non_trivial: If true, extract only scenarios with non-disjoint edits to files.
    Returns:
        A list of merge scenarios.
    """
    merge_commits = (
        commit for commit in repo.iter_commits() if len(commit.parents) == 2
    )
    if merge_commit_shas is not None:
        expected_merge_commits = set(merge_commit_shas)
        merge_commits = (
            commit
            for commit in merge_commits
            if commit.hexsha in expected_merge_commits
        )
    else:
        expected_merge_commits = set()

    merge_commits = list(merge_commits)
    merge_scenarios = []

    for merge in merge_commits:
        left, right = merge.parents
        base = repo.merge_base(*merge.parents, all=True)

        if not base:
            LOGGER.warning(
                f"No merge base for commits {left.hexsha} and {right.hexsha}"
            )
            continue
        elif len(base) > 1:
            LOGGER.warning(
                f"Multiple merge bases for {left.hexsha} and {right.hexsha}: {base}. "
                "Skipping to avoid recursive merge."
            )
            continue

        scenario = conts.MergeScenario(merge, base[0], left, right)

        if non_trivial and not extract_conflicting_files(repo, scenario):
            LOGGER.info(f"Skipping trivial merge commit {merge.hexsha}")
        else:
            LOGGER.info(f"Extracted merge commit {merge.hexsha}")
            expected_merge_commits -= {merge.hexsha}
            merge_scenarios.append(scenario)

    if expected_merge_commits:
        msg = f"Missing merge commits: {expected_merge_commits}"
        raise RuntimeError(msg)

    return merge_scenarios


def extract_all_conflicting_files(
    repo: git.Repo, merge_scenarios: Sequence[conts.MergeScenario],
) -> Iterable[conts.FileMerge]:
    return itertools.chain.from_iterable(
        extract_conflicting_files(repo, ms) for ms in merge_scenarios
    )


def extract_conflicting_files(
    repo: git.Repo,
    merge_scenario: conts.MergeScenario,
    skip_conflict_markers: bool = True,
) -> List[conts.FileMerge]:
    LOGGER.info(
        f"Extracting conflicting files for merge {merge_scenario.expected.hexsha}"
    )

    left = merge_scenario.left
    right = merge_scenario.right
    base = merge_scenario.base
    expected = merge_scenario.expected

    file_merges = []

    with merge_no_commit(
        repo,
        left.hexsha,
        right.hexsha,
        driver_config=FILE_MERGE_LOCATOR_DRIVER_CONFIG,
    ) as merge:
        _, merge_output = merge

        if _contains_non_content_conflict(merge_output):
            LOGGER.warning(
                f"Merge scenario {expected.hexsha} contains "
                "non-content conflict, can't safely extract expected "
                "revision. Skipping."
            )
            return []

        LOGGER.debug(merge_output)
        auto_merged = extract_automerged_files(merge_output)
        filemergelocator_results_file = (
            pathlib.Path(repo.working_tree_dir)
            / FILE_MERGE_LOCATOR_OUTPUT_NAME
        )

        if filemergelocator_results_file.exists():
            filemergelocator_results = _extract_filemergelocator_blob_shas(
                filemergelocator_results_file
            )
            filemergelocator_results_file.unlink()

            for file in auto_merged:
                abspath = repo.working_tree_dir / file
                id_ = abspath.read_text(sys.getdefaultencoding()).strip()
                if id_ not in filemergelocator_results:
                    LOGGER.warning(
                        f"Could not file merge for expected revision of {file} "
                        f"in merge scenario {expected.hexsha}. "
                        "This is typically due to non-content changes "
                        "(e.g mode changes). Skipping."
                    )
                    continue
                (
                    left_blob_sha,
                    base_blob_sha,
                    right_blob_sha,
                ) = filemergelocator_results[id_]
                left_blob = get_blob_by_sha(repo, left, left_blob_sha)
                right_blob = get_blob_by_sha(repo, right, right_blob_sha)
                base_blob = get_blob_by_sha(repo, base, base_blob_sha)

                if left_blob is None or right_blob is None or base_blob is None:
                    continue

                expected_blob = get_blob_by_path(repo, expected, str(file))

                if expected_blob is None:
                    LOGGER.warning(
                        f"Could not find expected file {file} in merge commit "
                        f"{expected.hexsha}, skipping"
                    )
                else:
                    expected_blob = expected.tree[str(file)]
                    file_merges.append(
                        conts.FileMerge(
                            expected=expected_blob,
                            left=left_blob,
                            right=right_blob,
                            base=base_blob,
                            from_merge_scenario=merge_scenario,
                        )
                    )

    return file_merges


def _missing_blob_warning(blob_sha: str, commit: git.Commit):
    LOGGER.warning(
        f"Could not find blob {blob_sha} in commit {commit.hexsha}. "
        "We have seen this happen when our .git/config/attributes file "
        "interferes with line ending conversion, or when line ending "
        "conversion has changed across revisions. Skipping file merge."
    )


def get_blob_by_sha(
    repo: git.Repo, commit: git.Commit, blob_sha: str
) -> git.Blob:
    index = repo.index.from_tree(repo, commit.hexsha)
    for _, blob in index.iter_blobs():
        if blob.hexsha == blob_sha:
            return blob
    _missing_blob_warning(blob_sha, commit)
    return


def get_blob_by_path(
    repo: git.Repo, commit: git.Commit, blob_path: str
) -> Optional[git.Blob]:
    index = repo.index.from_tree(repo, commit.hexsha)
    for _, blob in index.iter_blobs():
        if blob.path == blob_path:
            return blob
    return None


def _extract_filemergelocator_blob_shas(
    results_file: pathlib.Path,
) -> Tuple[str, str, str]:
    """Extract the hexshas of blobs that the file merge locator driver printed
    to stdout.
    """
    chunk_size = 4
    results = dict()
    lines = (
        results_file.read_text(sys.getdefaultencoding()).strip().split("\n")
    )
    assert len(lines) % chunk_size == 0

    for i in range(0, len(lines), chunk_size):
        id_, *shas = lines[i : i + chunk_size]
        results[id_] = shas

    return results


def merge_driver_exists(
    driver_name: str, gitconfig: Optional[pathlib.Path] = None
) -> bool:
    """Check that the specified driver is present in the gitconfig file.

    Args:
        driver_name: Name of the merge driver.
        gitonfig: A gitconfig file. Defaults to the global gitconfig.
    Returns:
        True if there is a driver with the specified name.
    """
    gitconfig = gitconfig or git.config.get_config_path("global")
    parser = git.config.GitConfigParser(gitconfig)
    return any(
        True
        for key in parser.sections()
        if key.startswith("merge")
        if parser.get_value(key, "name") == driver_name
    )


def contains_non_content_conflict(
    repo: git.Repo, ms: conts.MergeScenario
) -> bool:
    """Check if the merge scenario any non-content conflict, such
    asdelete/modify or rename/rename. Such conflicts cannot be solved by a standard merge tool.

    Args:
        repo: A Git repo.
        ms: A merge scenario.
    Returns:
        True iff the scenario contains a delete/modify conflict.
    """
    with merge_no_commit(
        repo,
        ms.left.hexsha,
        ms.right.hexsha,
        driver_config=FILE_MERGE_LOCATOR_DRIVER_CONFIG,
    ) as merge:
        _, output = merge
        return _contains_non_content_conflict(output)


def _contains_non_content_conflict(merge_output: str) -> bool:
    matches = [
        match
        for match in re.findall(CONFLICT_PATTERN, merge_output)
        if match != "content"
    ]
    return len(matches) != 0


def extract_unmerged_files(
    repo: git.Repo, ms: conts.MergeScenario, file_ext: Optional[str] = None
) -> List[pathlib.Path]:
    """Extract a list of paths to all files that can't be trivially merged for
    the given merge scenario.

    Args:
        repo: A git repository.
        ms: A merge scenario.
        file_ext: Limit the search to the given file extension.
    Returns:
        A list of paths to unmerged files, relative to the root of the repository worktree.
    """
    index = repo.index.from_tree(
        repo, ms.base.hexsha, ms.left.hexsha, ms.right.hexsha
    )
    return [
        path
        for k in index.unmerged_blobs().keys()
        if file_ext is None or (path := pathlib.Path(k)).suffix == file_ext
    ]


def _has_conflict_marker(file_merge: conts.FileMerge) -> bool:
    return any(
        map(
            _contains_conflict_marker,
            [
                file_merge.expected,
                file_merge.base,
                file_merge.left,
                file_merge.right,
            ],
        )
    )


def _contains_conflict_marker(blob: git.Blob) -> bool:
    if blob == None:
        return False

    lines = blob.data_stream[-1].read().decode(encoding="utf8").split("\n")
    return any(
        [
            line
            for line in lines
            if line.startswith(START_CONFLICT) or line.startswith(END_CONFLICT)
        ]
    )


@contextlib.contextmanager
def merge_no_commit(
    repo: git.Repo,
    left_sha: str,
    right_sha: str,
    driver_config: Optional[Tuple[str, str]],
) -> ContextManager[bool]:
    """Returns a context manager that on enter performs the desired merge
    without committing, and on exit aborts the merge and restores the repo HEAD to
    the initial state.

    Args:
        repo: A git repo to merge in.
        left_sha: The hexsha of the left commit (the "current" version).
        right_sha: The hexsha of the right commit (the "other" version).
        driver_config: An optional tuple with (merge_driver_name, file_pattern)
            used to set the merge driver for the merge.
    Returns:
        A context manager that yields True on a merge without conflicts.
    """
    try:
        with saved_git_head(repo):
            checkout_clean(repo, left_sha)
            try:
                if driver_config:
                    set_merge_driver(repo, *driver_config)
                    LOGGER.info(f"Using merge driver config {driver_config}")
                output = repo.git.merge(right_sha, "--no-commit")
                success = True
            except git.GitCommandError as exc:
                output = str(exc)
                success = False
            except:
                LOGGER.error("An unexpected error ocurred")
                raise

            yield success, output
    finally:
        if driver_config:
            LOGGER.info(f"Clearing merge driver")
            clear_merge_driver(repo)
        repo.git.reset("--merge")


def extract_automerged_files(
    git_merge_output: str, ext=".java"
) -> List[pathlib.Path]:
    """Extract a list of automerged files from the output of a git merge. Must
    run `git merge` with at least info level 2 (which is the default).
    """
    auto_merged = []
    for line in git_merge_output.strip().split("\n"):
        stripped = line.strip()
        if stripped.startswith("Auto-merging") and (
            not ext or stripped.endswith(ext)
        ):
            auto_merged.append(pathlib.Path(stripped[len("Auto-merging ") :]))
    return auto_merged


@contextlib.contextmanager
def saved_git_head(repo: git.Repo) -> ContextManager[None]:
    """Create a context manager that automatically restores the repos HEAD when exited.

    Args:
        repo: A Git repository.
    Returns:
        A context manager that restores the Git HEAD on exit.
    """
    saved_head = repo.head.commit.hexsha
    LOGGER.debug(f"Repo HEAD saved at {saved_head}")

    exc = None
    try:
        yield
    except BaseException as e:
        exc = e
    finally:
        checkout_clean(repo, saved_head)
        LOGGER.debug(f"Restored repo HEAD to {saved_head}")
        if exc is not None:
            raise exc


def checkout_clean(repo: git.Repo, commitish: str) -> None:
    """Checkout to a commit and clean any untracked files and directories."""
    repo.git.clean("-xfd")
    repo.git.checkout(commitish, "--force")


def _to_file_merge(
    rev_map: Mapping[conts.Revision, git.Blob], ms: conts.MergeScenario
) -> conts.FileMerge:
    base = (
        rev_map[conts.Revision.BASE]
        if conts.Revision.BASE in rev_map
        else None
    )
    left = rev_map[conts.Revision.LEFT]
    right = rev_map[conts.Revision.RIGHT]
    expected = rev_map[conts.Revision.ACTUAL_MERGE]
    return conts.FileMerge(
        base=base,
        left=left,
        right=right,
        expected=expected,
        from_merge_scenario=ms,
    )


def clone_repo(
    repo_name: str, github_user: str, output_dir: Optional[pathlib.Path] = None
) -> git.Repo:
    """Clone a repo from GitHub and put it in
    'output_dir/github_user/repo_name', or just 'github_user/repo_name' if the
    output_dir is not specified.

    If the repo already exists locally, it is returned as-is, or copied to
    output_dir if specified.

    Args:
        repo_name: Name of the repository.
        github_user: Owner of the repository.
        output_dir: A directory to put the cloned repository in.
    Returns:
        A Git repo.
    """
    qualname = f"{github_user}/{repo_name}"

    output_dir = (
        output_dir if output_dir is not None else pathlib.Path(os.getcwd())
    )
    repo_path = output_dir / github_user / repo_name

    if not output_dir.exists():
        output_dir.mkdir(parents=True)

    if not repo_path.exists():
        url = f"https://github.com/{qualname}.git"
        LOGGER.info(f"Cloning repository from {url}")
        repo = git.Repo.clone_from(url, str(repo_path))
        LOGGER.info(f"Repository cloned to {repo_path}")
    else:
        LOGGER.info(f"Using existing local repository at {repo_path}")
        repo = git.Repo(str(repo_path))

    return repo


def hash_object(path: pathlib.Path) -> str:
    """Compute the SHA1 hash of a blob using Git's hash-object command.

    Args:
        path: Path to a file.
    Returns:
        The SHA1 hash of the content of the file.
    """
    if not path.is_file():
        raise FileNotFoundError(f"Not a file: {path}")

    proc = subprocess.run(
        ["git", "hash-object", str(path)], capture_output=True
    )
    if proc.returncode != 0:
        raise RuntimeError(f"hash-object exited non-zero on {path}")

    return proc.stdout.decode().strip()


def set_merge_driver(
    repo: git.Repo, driver_name: str, file_pattern: str
) -> None:
    """Set the merge driver for the given pattern by overwriting the repo-local
    .git/info/attributes file.

    Args:
        repo: A git repo.
        driver_name: Name of the merge driver to use. Must be configured in the
            global .gitconfig file.
        file_pattern: A filename pattern to associate the driver with.
    """
    if not merge_driver_exists(driver_name):
        raise EnvironmentError(f"Merge driver '{driver_name}' does not exist")
    attributes_file = pathlib.Path(repo.git_dir) / "info" / "attributes"
    attributes_file.parent.mkdir(exist_ok=True)
    attributes_file.write_text(
        f"{file_pattern} merge={driver_name}",
        encoding=sys.getdefaultencoding(),
    )


def clear_merge_driver(repo: git.Repo) -> None:
    """Remove the repository-local attributes file."""
    (pathlib.Path(repo.git_dir) / "info" / "attributes").unlink()


def _get_blob(repo: git.Repo, commit_sha: str, blob_sha: str) -> git.Blob:
    commit = repo.commit(commit_sha)
    tree = commit.tree
    return tree[blob_sha]
