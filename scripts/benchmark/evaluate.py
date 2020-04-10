"""Module for evaluating the quality of a merge."""
import pathlib
import subprocess
import sys
import collections
import itertools
import dataclasses

from typing import List

START_CONFLICT = "<<<<<<<"
MID_CONFLICT = "======="
END_CONFLICT = ">>>>>>>"


@dataclasses.dataclass(frozen=True)
class MergeConflict:
    left: List[str]
    right: List[str]

    def pretty_print_conflict(self) -> str:
        return (
            f"{START_CONFLICT}\n{''.join(self.left)}{MID_CONFLICT}"
            f"\n{''.join(self.right)}{END_CONFLICT}"
        )

    @property
    def num_lines(self):
        """The amount of conflicting lines."""
        return len(self.left) + len(self.right)


def gumtree_edit_script(base_file: pathlib.Path, dest_file: pathlib.Path,) -> List[str]:
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


def git_diff_edit_script(base_file: pathlib.Path, dest_file: pathlib.Path, strip_metadata: bool=False) -> List[str]:
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

    lines = output.rstrip().split("\n")
    if strip_metadata and lines:
        # first 4 lines are metadata, lines starting with @@ is metadata
        lines = [line for line in lines[4:] if not line.startswith("@@")]

    return lines

def normalized_comparison(base_file: pathlib.Path, dest_file: pathlib.Path) -> bool:
    """Compare the base file to the destination file with a normalized AST comparison.

    Args:
        base_file: The base version of the file.
        dest_file: The edited version of the file.
    Returns:
        The normalized comparison distance.
    """
    cmd = ["spork", "compare", str(base_file), str(dest_file)]
    proc = subprocess.run(cmd, shell=False, capture_output=True)

    if proc.returncode != 0:
        return -1

    lines = [
        line.strip()
        for line in proc.stdout.decode(sys.getdefaultencoding()).split("\n")
        if line.strip()
    ]
    return int(lines[-1])


def extract_conflicts(path: pathlib.Path) -> List[MergeConflict]:
    """Extract merge conflicts from the given path.

    Args:
        path: Path to a Java file.
    Returns:
        A list of merge conflicts
    """
    with path.open(mode="r", encoding=sys.getdefaultencoding()) as file:
        lines = iter(file.readlines())

    conflicts = []

    def _extract_conflict():
        left = list(
            itertools.takewhile(lambda line: not line.startswith(MID_CONFLICT), lines)
        )
        right = list(
            itertools.takewhile(lambda line: not line.startswith(END_CONFLICT), lines)
        )
        return MergeConflict(left, right)

    while True:
        try:
            line = next(lines)
        except StopIteration:
            break

        if line.startswith(START_CONFLICT):
            conflicts.append(_extract_conflict())

    return conflicts
