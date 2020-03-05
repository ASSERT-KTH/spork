import sys
import time
import tempfile
import collections
import dataclasses
import pathlib
import subprocess
import git
import argparse

from typing import List


@dataclasses.dataclass(frozen=True)
class MergeScenario:
    result: git.Commit
    base: git.Commit
    left: git.Commit
    right: git.Commit


def extract_merge_scenarios(repo: git.Repo):
    merge_commits = [
        commit for commit in repo.iter_commits() if len(commit.parents) == 2
    ]

    merge_scenarios = []

    for merge in merge_commits:
        left, right = merge.parents
        base = repo.merge_base(*merge.parents)

        if not base:
            print(f"No merge base for commits {left.hexsha} and {right.hexsha}")
            continue
        elif len(base) > 1:
            print(
                f"Ambiguous merge base for commits {left.hexsha} and {right.hexsha}: {base}"
            )
            continue

        merge_scenarios.append(MergeScenario(merge, base[0], left, right))

    return merge_scenarios


def insert(revision, diffs: List[git.Commit], mapping):
    for d in diffs:
        base = d.a_blob
        rev = d.b_blob

        if not base or not rev:  # deletion or addition of a file, not interesting
            assert d.change_type in ["A", "D"]
            continue

        if base and base.name.endswith(".java") or rev and rev.name.endswith(".java"):
            mapping[d.a_blob.hexsha]["base"] = base
            mapping[d.a_blob.hexsha][revision] = rev


def create_merge_dirs(merge_dir_base, commit_sha, mapping):
    merge_dir = merge_dir_base / commit_sha
    merge_dir.mkdir()

    for key, val in mapping.items():
        scenario_dir = merge_dir / key
        scenario_dir.mkdir()

        base_content = val.get("base") or val.get("result")
        expected_content = val.get("result") or base_content

        write_blob_to_file(scenario_dir / "Left.java", val.get("left") or base_content)
        write_blob_to_file(
            scenario_dir / "Right.java", val.get("right") or base_content
        )
        write_blob_to_file(scenario_dir / "Base.java", base_content)
        write_blob_to_file(scenario_dir / "Expected.java", expected_content)


def write_blob_to_file(filepath, blob):
    filepath.write_bytes(blob.data_stream[-1].read())


def run_merge(scenario_dir, merge_cmd, compare_cmd):
    base = scenario_dir / "Base.java"
    left = scenario_dir / "Left.java"
    right = scenario_dir / "Right.java"
    expected = scenario_dir / "Expected.java"
    merge = scenario_dir / "Merge.java"

    assert base.is_file()
    assert left.is_file()
    assert right.is_file()
    assert expected.is_file()

    merge_proc = subprocess.run(
        f"{merge_cmd} {left} {base} {right} -o {merge}".split(), capture_output=True,
    )

    if not merge.is_file():
        print(f"merge failed to produce a Merge.java file")
        print(merge_proc.stdout.decode(sys.getdefaultencoding()))
        print(merge_proc.stderr.decode(sys.getdefaultencoding()))
        return False

    cmp_proc = subprocess.run(
        f"{compare_cmd} {expected} {merge}".split(), capture_output=True
    )

    if cmp_proc.returncode != 0:
        print(f"failed on blob {scenario_dir.name}")
        print(cmp_proc.stderr.decode(sys.getdefaultencoding()))
        print(cmp_proc.stdout.decode(sys.getdefaultencoding()))
        return False
    else:
        print(f"passed on blob {scenario_dir.name}")
        return True


def create_cli_parser():
    parser = argparse.ArgumentParser(
        "Spork merge tester", description="A little program to help develop Spork!"
    )

    parser.add_argument(
        "--merge-cmd", help="Merge command.", type=str, required=True,
    )
    parser.add_argument(
        "--cmp-cmd", help="Compare command.", type=str, required=True,
    )
    parser.add_argument(
        "-r",
        "--repo",
        help="Name of the repo to run tests on",
        type=str,
        required=True,
    )
    parser.add_argument(
        "-g",
        "--github-user",
        help="GitHub username to fetch repo from. Is combined with `--repo`"
        "to form a qualified repo name on the form `repo/user`. If this is "
        "not provided, the repo argument is assumend to be a local directory.",
        type=str,
    )
    parser.add_argument(
        "-n",
        "--num-merges",
        help="Maximum amount of merges to recreate.",
        type=int,
        default=100,
    )

    return parser


def main():
    parser = create_cli_parser()
    args = parser.parse_args(sys.argv[1:])

    if args.github_user is not None:
        qualname = f"{args.github_user}/{args.repo}"

        if not pathlib.Path(qualname).exists():
            url = f"https://github.com/{qualname}.git"
            repo = git.Repo.clone_from(url, qualname)
        else:
            repo = git.Repo(qualname)
    else:
        repo = git.Repo(args.repo)

    merge_scenarios = extract_merge_scenarios(repo)[: args.num_merges]

    num_passed = 0
    num_failed = 0

    print(f"recreating {len(merge_scenarios)} merges")

    for merge in merge_scenarios[:50]:
        diff_left = merge.base.diff(merge.left)
        diff_right = merge.base.diff(merge.right)
        diff_result = merge.base.diff(merge.result)

        mapping = collections.defaultdict(dict)
        insert("left", diff_left, mapping)
        insert("right", diff_right, mapping)
        insert("result", diff_result, mapping)

        #if merge.result.hexsha == "08ebba7998647c258437132baadad922c8b43419":
        #    continue

        print(f"running merge scenarios for commit {merge.result.hexsha}")

        start = time.time_ns()
        with tempfile.TemporaryDirectory() as tmpdir:
            merge_base_dir = pathlib.Path(tmpdir)
            create_merge_dirs(merge_base_dir, merge.result.hexsha, mapping)
            for merge_dir in merge_base_dir.iterdir():
                assert merge_dir.is_dir()

                for scenario_dir in merge_dir.iterdir():
                    assert scenario_dir.is_dir()
                    if run_merge(scenario_dir, args.merge_cmd, args.cmp_cmd):
                        num_passed += 1
                    else:
                        num_failed += 1

        end = time.time_ns()
        delta = (end - start) / 1e9
        print(f"passed: {num_passed}, failed: {num_failed}")
        print(f"Time elapsed: {delta} seconds")


if __name__ == "__main__":
    main()
