"""Utility functions for interacting with .class and .java files."""

import pathlib
import tempfile
import subprocess
import shutil
import sys
from typing import List

import daiquiri

LOGGER = daiquiri.getLogger(__name__)


def compare_compiled_bytecode(
    expected_compile: pathlib.Path,
    replayed_compile: pathlib.Path,
    changed_sourcefiles: List[pathlib.Path],
):
    """Run the bytecode comparison evaluation.

    Args:
        expected_compile: Root directory of the compiled output from the
            expected revision.
        replayed_compile: Root directory of the compiled output from the
            replayed revision.
        changed_sourcefiles: Paths to source files that were changed during the
            replay, i.e. source files part in non-trivial merging.
    Returns:
        True if the classfiles corresponding to all changed sourcefiles in the
        expected and replayed compile outputs are equal.
    """
    num_classfiles = 0
    num_equal = 0
    for src in changed_sourcefiles:
        refs = locate_classfiles(src, basedir=expected_compile)
        others = locate_classfiles(src, basedir=replayed_compile)

        if [p.name for p in refs] != [p.name for p in others]:
            raise RuntimeError(
                f"Discrepancy between classfile lists. refs={refs}, others={others}"
            )

        num_classfiles += len(refs)

        for ref, other in zip(refs, others):
            LOGGER.info(f"Removing duplicate checkcasts from {ref.name} revisions ...")
            remove_duplicate_checkcasts(ref)
            remove_duplicate_checkcasts(other)

            LOGGER.info(f"Comparing {ref.name} revisions ...")
            if compare_classfiles(ref, other):
                LOGGER.info(f"{ref.name} revision are equal")
                num_equal += 1
            else:
                LOGGER.warning(f"{ref.name} revisions not equal")

    return num_equal == num_classfiles


def compare_classfiles(ref: pathlib.Path, other: pathlib.Path) -> bool:
    """Compare two classfiles with normalized bytecode equality using sootdiff.
    Requires sootdiff to be on the path.

    Args:
        ref: The reference classfile.
        other: The other classfile.
    Returns:
        True if the files are equal
    """
    if ref.name != other.name:
        raise ValueError("Cannot compare two classfiles from different classes")

    ref_pkg = extract_java_package(ref)
    other_pkg = extract_java_package(other)

    if ref_pkg != other_pkg:
        return False

    with tempfile.TemporaryDirectory() as tmpdir:
        ref_dirpath = pathlib.Path(tmpdir) / "ref"
        other_dirpath = pathlib.Path(tmpdir) / "other"
        pkg_relpath = pathlib.Path(*ref_pkg.split("."))

        ref_pkg_dirpath = ref_dirpath / pkg_relpath
        ref_pkg_dirpath.mkdir(parents=True)
        other_pkg_dirpath = other_dirpath / pkg_relpath
        other_pkg_dirpath.mkdir(parents=True)

        shutil.copy(ref, ref_pkg_dirpath / ref.name)
        shutil.copy(other, other_pkg_dirpath / other.name)

        qualname = f"{ref_pkg}.{ref.stem}"

        proc = subprocess.run(
            [
                "sootdiff",
                "-qname",
                qualname,
                "-reffile",
                str(ref_dirpath),
                "-otherfile",
                str(other_dirpath),
            ],
        )

        return proc.returncode == 0


def locate_classfiles(src: pathlib.Path, basedir: pathlib.Path) -> List[pathlib.Path]:
    """Locate the classfiles corresponding to the source file. Requires the
    pkgextractor utility to be on the path.

    Note that any source file with multiple classes, be that nested or several
    non-public classes, will generate multiple classfiles for the same source
    file.

    Args:
        src: The source file.
        basedir: Base directory of some compiled output.
    Returns:
        A sorted list of classfile names.
    """
    if not src.is_file():
        raise FileNotFoundError(f"No such file {src}")
    name = src.stem
    matches = [file for file in basedir.rglob("*.class") if file.stem == name]
    expected_pkg = extract_java_package(src)

    classfiles = [
        classfile
        for classfile in matches
        if extract_java_package(classfile) == expected_pkg
    ]

    if not classfiles:
        raise RuntimeError(f"Unable to locate classfile corresponding to {src}")
    if len(classfiles) > 1:
        raise RuntimeError(f"Found multiple matching classfiles to {src}: {classfiles}")

    return sorted(classfiles, key=lambda path: path.name)


def remove_duplicate_checkcasts(path: pathlib.Path) -> None:
    """Remove duplicate checkcast instructions from a classfile and overwrite the
    original file.

    Due to a bug in JDK8, a typecast on a parenthesized expression will
    generate two checkcast instructions, instead of one. As a checkcast
    instruction does not alter the state of the JVM unless the check fails,
    having two in a row is the epitamy of redundancy.

    This function requires the duplicate-checkcast-remover tool to be on the
    path.

    Args:
        path: Path to a .class file.
    """
    if not path.suffix == ".class":
        raise ValueError(f"Not a .class file: {path}")

    proc = subprocess.run(["duplicate-checkcast-remover", str(path)])
    if proc.returncode != 0:
        raise RuntimeError(f"Failed to run duplicate-checkast-remover on {path}")


def extract_java_package(path: pathlib.Path) -> str:
    """Extract the package statement from a .java or .class file. Requires the
    pkgextractor to be on the path.
    """
    return (
        subprocess.run(["pkgextractor", str(path)], capture_output=True)
        .stdout.decode(encoding=sys.getdefaultencoding())
        .strip()
    )
