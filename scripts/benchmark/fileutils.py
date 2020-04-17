"""Utility functions for files and directories."""
import collections
import pathlib
import sys
import re
import dataclasses
import subprocess
import re
from typing import List, Mapping, Tuple, Iterable

import git
import daiquiri

from . import gitutils

LOGGER = daiquiri.getLogger(__name__)

BLOB_SHA_SEP = "_"

INLINE_COMMENT_PATTERN = re.compile("//.*")
# the (?s) flag is equivalent to the re.DOTALL flag
BLOCK_COMMENT_PATTERN = re.compile("(?s)/\*.*?\*/")


def create_merge_dirs(
    merge_dir_base: pathlib.Path,
    file_merges: Iterable[gitutils.FileMerge],
    strip_comments: bool = False,
) -> List[pathlib.Path]:
    """Create merge directories based on the provided merge scenarios. For each merge scenario A,
    a merge directory A is created. For each file to be merged, a subdirectory with base, left, right and
    expected merge revisions are created.
    """
    merge_dirs = []

    for file_merge in file_merges:
        result_blob = file_merge.result
        ms = file_merge.from_merge_scenario
        merge_commit = ms.result
        left_commit = ms.left
        right_commit = ms.right
        base_commit = ms.base

        left_blob = file_merge.left
        right_blob = file_merge.right

        left_filename = create_blob_filename("Left", left_blob, left_commit)
        right_filename = create_blob_filename("Right", right_blob, right_commit)
        result_filename = create_blob_filename("Expected", result_blob, merge_commit)

        merge_dir = merge_dir_base / merge_commit.hexsha / result_blob.name
        merge_dir.mkdir(parents=True)

        _write_blob_to_file(merge_dir / result_filename, result_blob, strip_comments)
        _write_blob_to_file(merge_dir / left_filename, left_blob, strip_comments)
        _write_blob_to_file(merge_dir / right_filename, right_blob, strip_comments)
        if file_merge.base:
            _write_blob_to_file(
                merge_dir / create_blob_filename("Base", file_merge.base, base_commit),
                file_merge.base,
                strip_comments,
            )
        else:
            # If left and right added the same file, there will be no base blob
            LOGGER.warning(
                f"No base blob for merge commit {merge_commit.hexsha} and result blob {result_blob.hexsha}"
            )
            (merge_dir / f"Base{BLOB_SHA_SEP}{BLOB_SHA_SEP}.java").write_bytes(b"")

        merge_dirs.append(merge_dir)

    return merge_dirs


def extract_commit_sha(merge_dir: pathlib.Path) -> str:
    """Extract the commit sha from a merge directory path."""
    return list(merge_dir.parents)[0].name

def extract_commit_sha_from_filepath(filepath: pathlib.Path) -> str:
    """Extract the commit sha from a Java file in a merge directory."""
    return filepath.name[:-5].split(BLOB_SHA_SEP)[-2]

def extract_blob_sha(filepath: pathlib.Path) -> str:
    """Extract the blob commit sha from a Java file in a merge directory."""
    return filepath.name[:-5].split(BLOB_SHA_SEP)[-1]


def count_lines(filepath: pathlib.Path) -> int:
    """Count the lines of the given file."""
    with filepath.open(mode="r", encoding=sys.getdefaultencoding()) as f:
        return len([1 for _ in f.readlines()])


def mvn_compile(workdir: pathlib.Path):
    """Compile the project in workdir with mvn."""
    proc = subprocess.run("mvn clean compile".split(), cwd=workdir)
    return proc.returncode == 0


def create_blob_filename(prefix: str, blob: git.Blob, commit: git.Commit, ext: str = "java") -> str:
    """Create the filename for a blob."""
    return f"{prefix}{BLOB_SHA_SEP}{commit.hexsha}{BLOB_SHA_SEP}{blob.hexsha}.{ext}"


def read_non_empty_lines(path: pathlib.Path) -> List[str]:
    """Read all non-empty lines from the path, stripping any leading and trailing whitespace."""
    return [
        line.strip()
        for line in path.read_text(encoding=sys.getdefaultencoding()).split("\n")
        if line.strip()
    ]


def strip_comments(java_code: str) -> str:
    """Strip inline and block comments from the provided source code."""
    block_comments_stripped = re.sub(BLOCK_COMMENT_PATTERN, "", java_code)
    return re.sub(INLINE_COMMENT_PATTERN, "", block_comments_stripped)


def copy_withouth_comments(src: pathlib.Path, dst: pathlib.Path) -> None:
    """Copy the content in src to dst, stripping any Java comments."""
    content = src.read_text(sys.getdefaultencoding())
    stripped = strip_comments(content)
    dst.touch()
    dst.write_text(stripped, encoding=sys.getdefaultencoding())


def _write_blob_to_file(filepath, blob, strip_comments):
    data = blob.data_stream[-1].read()
    if strip_comments:
        content = data.decode(sys.getdefaultencoding())
        stripped = strip_comments(content)
        data = stripped.encode(sys.getdefaultencoding())
    filepath.write_bytes(data)
