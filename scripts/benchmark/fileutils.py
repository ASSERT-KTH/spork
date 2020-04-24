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

LOGGER = daiquiri.getLogger(__name__)

INLINE_COMMENT_PATTERN = re.compile("(?m)^\s*//.*")
# (?s) is equivalent to the re.DOTALL flag
BLOCK_COMMENT_PATTERN = re.compile("(?ms)^\s*/\*.*?\*/")
# (?m) is equivalent to the re.MULTILINE flag
BLANK_LINE_PATTERN = re.compile(r"(?m)^\s*\n")
EMPTY_BLOCK_PATTERN = re.compile(r"{\s+}")

NORMALIZED_FILE_SUFFIX = "_normalized.java"


def create_merge_dirs(merge_dir_base: pathlib.Path, file_merges,) -> List[pathlib.Path]:
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

        merge_dir = merge_dir_base / merge_commit.hexsha / result_blob.name
        merge_dir.mkdir(parents=True)

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


def extract_commit_sha(merge_dir: pathlib.Path) -> str:
    """Extract the commit sha from a merge directory path."""
    return list(merge_dir.parents)[0].name


def count_lines(filepath: pathlib.Path) -> int:
    """Count the lines of the given file."""
    with filepath.open(mode="r", encoding=sys.getdefaultencoding()) as f:
        return len([1 for _ in f.readlines()])


def count_nodes(filepath: pathlib.Path) -> int:
    """Count the amount of GumTree nodes in the given Java file. Requires
    count-nodes to be on the path.
    """
    proc = subprocess.run(["count-nodes", str(filepath)], capture_output=True)

    if proc.returncode != 0:
        raise RuntimeError(f"count-nodes exited non-zero on {filepath}")

    return int(proc.stdout.decode().strip())


def mvn_compile(workdir: pathlib.Path):
    """Compile the project in workdir with mvn."""
    proc = subprocess.run("mvn clean compile".split(), cwd=workdir)
    return proc.returncode == 0


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


def strip_blank_lines(text: str) -> str:
    """Remove any blank lines from the text."""
    lines = []
    for line in text.split("\n"):
        if line and not line.isspace():
            lines.append(line)
    return "\n".join(lines)


def normalize_formatting(java_code: str) -> str:
    """Normalize the formatting by removing comments, blank lines and removing
    whitespace in empty blocks.
    """
    for pattern in [
        INLINE_COMMENT_PATTERN,
        BLOCK_COMMENT_PATTERN,
        BLANK_LINE_PATTERN,
    ]:
        java_code = re.sub(pattern, "", java_code)
    return re.sub(EMPTY_BLOCK_PATTERN, r"{}", java_code)


def copy_normalized(
    src: pathlib.Path, overwrite_existing: bool = False
) -> pathlib.Path:
    """Copy the content in src, normalize it and write it to a new file with
    the NORMALIZED_FILE_SUFFIX extension.

    Args:
        src: source Java file.
        overwrite_existing: If True, existing files are overwritten.
    Returns:
        The path to the normalized file.
    """
    dst = src.parent / (src.stem + NORMALIZED_FILE_SUFFIX)
    if not overwrite_existing and dst.exists():
        return dst

    content = src.read_text(sys.getdefaultencoding())
    normalized = normalize_formatting(content)
    dst.touch()
    dst.write_text(normalized, encoding=sys.getdefaultencoding())
    return dst


def _write_blob_to_file(filepath, blob):
    data = blob.data_stream[-1].read()
    filepath.write_bytes(data)
