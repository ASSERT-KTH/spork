"""Utility functions for files and directories."""
import collections
import pathlib
import sys
import re
from typing import List, Mapping, Tuple

import git
import daiquiri

from . import gitutils

LOGGER = daiquiri.getLogger(__name__)


def create_merge_dirs(
    merge_dir_base: pathlib.Path,
    file_merges: List[Tuple[git.Commit, List[Mapping[gitutils.Revision, git.Blob]]]],
    strip_comments: bool = True,
) -> List[pathlib.Path]:
    """Create merge directories based on the provided merge scenarios. For each merge scenario A,
    a merge directory A is created. For each file to be merged, a subdirectory with base, left, right and
    expected merge revisions are created.
    """
    merge_dirs = []

    for merge_commit, merge_revisions_list in file_merges:
        for merge_revisions in merge_revisions_list:
            result_blob = merge_revisions[gitutils.Revision.ACTUAL_MERGE]

            if not str(result_blob.name).endswith(".java"):
                LOGGER.warning(f"{result_blob.name} is not a Java file, skipping")
                continue

            left_blob = merge_revisions[gitutils.Revision.LEFT]
            right_blob = merge_revisions[gitutils.Revision.RIGHT]

            merge_dir = merge_dir_base / merge_commit.hexsha / result_blob.name
            merge_dir.mkdir(parents=True)

            _write_blob_to_file(
                merge_dir / "Expected.java", result_blob, strip_comments
            )
            _write_blob_to_file(merge_dir / "Left.java", left_blob, strip_comments)
            _write_blob_to_file(merge_dir / "Right.java", right_blob, strip_comments)
            if gitutils.Revision.BASE in merge_revisions:
                _write_blob_to_file(
                    merge_dir / "Base.java",
                    merge_revisions[gitutils.Revision.BASE],
                    strip_comments,
                )
            else:
                # If left and right added the same file, there will be no base blob
                LOGGER.warning(
                    f"No base blob found for merge commit {merge_commit.hexsha} and result blob {result_blob.hexsha}"
                )
                (merge_dir / "Base.java").write_bytes(b"")

            merge_dirs.append(merge_dir)

    return merge_dirs


def _write_blob_to_file(filepath, blob, strip_comments):
    data = blob.data_stream[-1].read()
    if strip_comments:
        content = data.decode(sys.getdefaultencoding())
        content = re.sub("//.*", "", content)
        content = re.sub(r"/\*.*?\*/", "", content, flags=re.DOTALL)
        data = content.encode(sys.getdefaultencoding())
    filepath.write_bytes(data)
