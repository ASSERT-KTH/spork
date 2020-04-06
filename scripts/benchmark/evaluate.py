"""Module for evaluating the quality of a merge."""
import pathlib
import subprocess
import sys

from typing import List


def gumtree_edit_script(
    base_file: pathlib.Path, dest_file: pathlib.Path,
) -> List[str]:
    """Return the the edit script produced by vanilla GumTree diff, not
    including all of the lines that say "Match".

    Args:
        gumtree_diff: Path to the gumtree binary.
        base_file: The base version of the file.
        dest_file: The edited version of the file.
    Returns:
        The edit script produced by GumTree diff.
    """
    cmd = [str(v) for v in ["gumtree", "diff", base_file, dest_file]]
    proc = subprocess.run(cmd, shell=False, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(
            "GumTree diff exited non-zero", proc.stderr.decode(sys.getdefaultencoding())
        )

    output = proc.stdout.decode(sys.getdefaultencoding())
    return [
        line
        for line in output.split("\n")
        if not line.startswith("Match") and line.strip()
    ]


def git_diff_edit_script(base_file: pathlib.Path, dest_file: pathlib.Path) -> List[str]:
    """Return the edit script produced by git diff. Requires that the `git`
    program is on the path.

    Args:
        base_file: The base version of the file.
        dest_file: The edited version of the file.
    Returns:
        The edit script produced by git diff, ignoring carriege returns,
        whitespace and blank lines.
    """

    git_diff = (
        "git diff --ignore-cr-at-eol --ignore-all-space "
        "--ignore-blank-lines --ignore-space-change --no-index -U0"
    ).split()
    cmd = [*git_diff, str(base_file), str(dest_file)]
    proc = subprocess.run(cmd, shell=False, capture_output=True)

    if proc.returncode == 0:
        # zero exit code means there were no differences
        return []

    output = proc.stdout.decode(sys.getdefaultencoding())
    return [line for line in output.split("\n") if line.strip()]
