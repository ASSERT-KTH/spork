"""Utility functions for interacting with .class and .java files."""

import pathlib
import collections
import tempfile
import subprocess
import shutil
import sys
from typing import List, Iterable

import daiquiri

from . import containers as conts

LOGGER = daiquiri.getLogger(__name__)


def compare_compiled_bytecode(
    replayed_compile_basedir: pathlib.Path,
    expected_classfiles: List[pathlib.Path],
):
    """Run the bytecode comparison evaluation.

    Args:
        expected_classfiles: Classfiles from the expected revision.
    Returns:
        True if each of the expected classfiles are present in the replayed
        compile output, and each pair of classfiles are equal.
    """
    num_equal = 0

    classfile_pairs = generate_classfile_pairs(
        expected_classfiles, replayed_compile_basedir
    )
    for pair in classfile_pairs:
        if pair.replayed is None:
            LOGGER.warning(
                f"No replayed classfile corresponding to {pair.expected.name}"
            )
            continue

        LOGGER.info(
            f"Removing duplicate checkcasts from replayed revision of {pair.replayed.name}"
        )
        remove_duplicate_checkcasts(pair.replayed)

        LOGGER.info(f"Comparing {pair.replayed.name} revisions ...")
        if compare_classfiles(pair):
            LOGGER.info(f"{pair.replayed.name} revision are equal")
            num_equal += 1
        else:
            LOGGER.warning(f"{pair.replayed.name} revisions not equal")

    return num_equal == len(expected_classfiles)


def compare_classfiles(pair: conts.ClassfilePair) -> bool:
    """Compare two classfiles with normalized bytecode equality using sootdiff.
    Requires sootdiff to be on the path.

    Args:
        pair: The pair of classfiles.
    Returns:
        True if the files are equal
    """
    ref = pair.expected
    other = pair.replayed
    if ref.name != other.name:
        raise ValueError(
            "Cannot compare two classfiles from different classes"
        )

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


def locate_classfiles(
    src: pathlib.Path, basedir: pathlib.Path
) -> List[pathlib.Path]:
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
        raise RuntimeError(
            f"Unable to locate classfile corresponding to {src}"
        )
    if len(classfiles) > 1:
        raise RuntimeError(
            f"Found multiple matching classfiles to {src}: {classfiles}"
        )

    return sorted(classfiles, key=lambda path: path.name)


def generate_classfile_pairs(
    expected_classfiles: List[pathlib.Path], replayed_basedir: pathlib.Path
) -> Iterable[conts.ClassfilePair]:
    """For each classfile in the classfiles list, find the corresponding
    classfile in the replayed basedir and create a pair. If no corresponding
    classfile can be found, None is used in its place.

    Args:
        expected_classfiles: A list of the expected classfiles.
        replayed_basedir: Base directory to search for matching classfiles.
    Returns:
        A generator of classfile pairs.
    """
    potential_replayed_matches = {
        path
        for path in replayed_basedir.rglob("*.class")
        if path.name
        in (expected_classfile_names := {p.name for p in expected_classfiles})
    }
    for expected in expected_classfiles:
        matches = [
            replayed
            for replayed in potential_replayed_matches
            if replayed.name == expected.name
            and extract_java_package(replayed)
            == extract_java_package(expected)
        ]
        assert len(matches) <= 1
        replayed = matches[0] if matches else None
        yield conts.ClassfilePair(expected=expected, replayed=replayed)


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
        raise RuntimeError(
            f"Failed to run duplicate-checkast-remover on {path}"
        )


def extract_java_package(path: pathlib.Path) -> str:
    """Extract the package statement from a .java or .class file. Requires the
    pkgextractor to be on the path.
    """
    return (
        subprocess.run(["pkgextractor", str(path)], capture_output=True)
        .stdout.decode(encoding=sys.getdefaultencoding())
        .strip()
    )
