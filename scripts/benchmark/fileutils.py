"""Utility functions for files and directories."""
import collections
import pathlib
import shutil
import sys
import re
import dataclasses
import subprocess
import re
import tempfile
import hashlib
from typing import List, Mapping, Tuple, Iterable

import git
import daiquiri

LOGGER = daiquiri.getLogger(__name__)


def create_merge_dirs(
    merge_dir_base: pathlib.Path, file_merges,
) -> List[pathlib.Path]:
    """Create merge directories based on the provided merge scenarios. For each merge scenario A,
    a merge directory A is created. For each file to be merged, a subdirectory with base, left, right and
    expected merge revisions are created.
    """
    merge_dirs = []

    for file_merge in file_merges:
        result_blob = file_merge.expected
        ms = file_merge.from_merge_scenario
        merge_commit = ms.expected

        left_blob = file_merge.left
        right_blob = file_merge.right

        left_filename = "Left.java"
        right_filename = "Right.java"
        result_filename = "Expected.java"

        merge_dir_name = create_unique_filename(path=result_blob.path, name=result_blob.name)
        merge_dir = merge_dir_base / merge_commit.hexsha / merge_dir_name
        merge_dir.mkdir(parents=True, exist_ok=True)

        _write_blob_to_file(merge_dir / result_filename, result_blob)
        _write_blob_to_file(merge_dir / left_filename, left_blob)
        _write_blob_to_file(merge_dir / right_filename, right_blob)
        if file_merge.base:
            _write_blob_to_file(
                merge_dir / "Base.java", file_merge.base,
            )
        else:
            # If left and right added the same file, there will be no base blob
            LOGGER.warning(
                f"No base blob for merge commit {merge_commit.hexsha} and result blob {result_blob.hexsha}"
            )
            (merge_dir / "Base.java").write_bytes(b"")

        merge_dirs.append(merge_dir)

    return merge_dirs


def _write_blob_to_file(filepath, blob):
    data = blob.data_stream[-1].read()
    filepath.write_bytes(data)


def extract_commit_sha(merge_dir: pathlib.Path) -> str:
    """Extract the commit sha from a merge directory path."""
    return list(merge_dir.parents)[0].name


def count_lines(filepath: pathlib.Path) -> int:
    """Count the lines of the given file."""
    with filepath.open(mode="r", encoding=sys.getdefaultencoding()) as f:
        return len([1 for _ in f.readlines()])


def read_non_empty_lines(path: pathlib.Path) -> List[str]:
    """Read all non-empty lines from the path, stripping any leading and trailing whitespace."""
    return [
        line.strip()
        for line in path.read_text(encoding=sys.getdefaultencoding()).split(
            "\n"
        )
        if line.strip()
    ]

def create_unique_filename(path, name: str) -> str:
    """Create a unique name for the stem of this path."""
    path_sha = hashlib.sha1(str(path).encode(sys.getdefaultencoding())).hexdigest()
    return f"{name}_{path_sha}"
