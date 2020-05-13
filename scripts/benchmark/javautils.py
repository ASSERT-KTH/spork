"""Utility functions for interacting with .class and .java files."""

import pathlib
import collections
import tempfile
import subprocess
import shutil
import sys
from typing import List, Iterable, Tuple

import daiquiri

from . import containers as conts

LOGGER = daiquiri.getLogger(__name__)


def compare_compiled_bytecode(
    replayed_compile_basedir: pathlib.Path,
    expected_classfiles: List[conts.ExpectedClassfile],
    eval_dir: pathlib.Path,
    merge_driver: str,
):
    """Run the bytecode comparison evaluation.

    Args:
        expected_classfiles: Tuples of (classfile_copy_abspath,
            original_classfile_relpath), where the relative path is relative to
            the root of the repository.
    Returns:
        The amount of classfiles that compared equal.
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
        if compare_classfiles(pair, eval_dir, merge_driver):
            LOGGER.info(f"{pair.replayed.name} revision are equal")
            num_equal += 1
        else:
            LOGGER.warning(f"{pair.replayed.name} revisions not equal")

    return num_equal


def compare_classfiles(
    pair: conts.ClassfilePair, eval_dir: pathlib.Path, merge_driver: str
) -> bool:
    """Compare two classfiles with normalized bytecode equality using sootdiff.
    Requires sootdiff to be on the path.

    Args:
        pair: The pair of classfiles.
        eval_dir: Directory to perform evaluation in.
        merge_driver: The merge driver that produced the merge. Used to give
            the storage directory a name.
    Returns:
        True if the files are equal
    """
    expected = pair.expected
    replayed = pair.replayed
    if expected.name != replayed.name:
        raise ValueError(
            "Cannot compare two classfiles from different classes"
        )

    expected_pkg = extract_java_package(expected)
    replayed_pkg = extract_java_package(replayed)

    basedir = eval_dir / expected.name
    expected_basedir = basedir / "expected"
    replayed_basedir = basedir / merge_driver

    copy_to_pkg_dir(
        classfile=replayed, pkg=replayed_pkg, basedir=replayed_basedir
    )

    if expected_pkg != replayed_pkg:
        return False

    qualname = f"{expected_pkg}.{expected.stem}"

    try:
        proc = subprocess.run(
            [
                "sootdiff",
                "-qname",
                qualname,
                "-reffile",
                str(expected_basedir),
                "-otherfile",
                str(replayed_basedir),
            ],
            timeout=30,
        )
    except:
        LOGGER.exception("error running sootdiff")
        return False

    return proc.returncode == 0


def copy_to_pkg_dir(
    classfile: pathlib.Path, pkg: str, basedir: pathlib.Path,
) -> pathlib.Path:
    pkg_relpath = pathlib.Path(*pkg.split("."))
    pkg_abspath = basedir / pkg_relpath
    pkg_abspath.mkdir(parents=True, exist_ok=True)
    classfile_dst = pkg_abspath / classfile.name

    if classfile_dst.exists():
        raise FileExistsError(f"Classfile {classfile_dst} all ready exists!")

    shutil.copy(classfile, classfile_dst)
    return classfile_dst


def locate_classfiles(
    src: pathlib.Path, basedir: pathlib.Path
) -> (List[pathlib.Path], str):
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
        LOGGER.warning(
            f"{src} is not in {basedir}. This is typically caused by "
            "renaming that Git cannot detect"
        )
        return []
    name = src.stem
    matches = [file for file in basedir.rglob("*.class") if file.stem == name]
    expected_pkg = extract_java_package(src)

    classfiles = [
        classfile
        for classfile in matches
        if extract_java_package(classfile) == expected_pkg
    ]

    if not classfiles:
        LOGGER.warning(f"Found no classfiles corresponding to {src}")

    return (sorted(classfiles), expected_pkg)


def generate_classfile_pairs(
    expected_classfiles: List[conts.ExpectedClassfile],
    replayed_basedir: pathlib.Path,
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
    for expected in expected_classfiles:
        replayed_classfile = replayed_basedir / expected.original_relpath
        if replayed_classfile.exists():
            yield conts.ClassfilePair(
                expected=expected.copy_abspath, replayed=replayed_classfile
            )


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

    proc = subprocess.run(["duplicate-checkcast-remover", str(path)], timeout=60)
    if proc.returncode != 0:
        raise RuntimeError(
            f"Failed to run duplicate-checkast-remover on {path}"
        )


def extract_java_package(path: pathlib.Path) -> str:
    """Extract the package statement from a .java or .class file. Requires the
    pkgextractor to be on the path.
    """
    try:
        proc = subprocess.run(["pkgextractor", str(path)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    except:
        LOGGER.exception("error running pkgextractor")
        return ""

    if proc.returncode != 0:
        LOGGER.debug(proc.stdout.decode(sys.getdefaultencoding()))
        raise RuntimeError(
            f"pkgextractor failed to extract package from {path}"
        )
    return proc.stdout.decode(encoding=sys.getdefaultencoding()).strip()


def find_target_directories(project_root: pathlib.Path) -> List[pathlib.Path]:
    """Find target directories created by Maven."""

    def _contains_class_dirs(path):
        contained_dirs = {p.name for p in path.iterdir() if p.is_dir()}
        return "classes" in contained_dirs or "test-classes" in contained_dirs

    return [
        target_dir
        for target_dir in project_root.rglob("target")
        if _contains_class_dirs(target_dir)
    ]


def mvn_compile(workdir: pathlib.Path) -> Tuple[bool, bytes]:
    """Compile the project in workdir with Maven's test-compile command."""
    try:
        proc = subprocess.run(
            "mvn clean test-compile".split(),
            cwd=workdir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=5*60,
        )
    except:
        LOGGER.exception("error compiling project")
        return False, b""
    return proc.returncode == 0, proc.stdout


def mvn_test(workdir: pathlib.Path):
    """Run the project's test suite."""
    proc = subprocess.run("mvn clean test".split(), cwd=workdir, timeout=5*60)
    return proc.returncode == 0

