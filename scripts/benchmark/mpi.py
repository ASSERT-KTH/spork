"""Module for running benchmarks in parallel with MPI"""
import argparse
import sys
import pathlib
import csv

from typing import List

from mpi4py import MPI

from . import gitutils
from . import fileutils
from . import run
from . import evaluate
from . import gather
from . import reporter

comm = MPI.COMM_WORLD
num_procs = comm.Get_size()
rank = comm.Get_rank()
stat = MPI.Status()

MASTER_RANK = 0
NUM_WORKERS = num_procs - 1

merge_base_dir = None


def master(repo_name: str, github_user: str, num_merges: int):
    assert merge_base_dir is not None
    print("Hello world, I am the master creator!")
    merge_base_dir.mkdir()

    repo = gitutils.clone_repo(repo_name, github_user)
    merge_scenarios = gitutils.extract_merge_scenarios(repo, num_merges)
    file_merges = gitutils.extract_all_conflicting_files(repo, merge_scenarios)
    merge_dirs = fileutils.create_merge_dirs(merge_base_dir, file_merges)

    print(f"Extracted {len(merge_dirs)} file merges")

    dirs_per_proc = int(len(merge_dirs) / NUM_WORKERS)
    range_starts = [i * dirs_per_proc for i in range(NUM_WORKERS)]

    for i in range(num_procs - 1):
        start = range_starts[i]
        end = range_starts[i + 1] if i < len(range_starts) - 1 else len(merge_dirs)
        proc_dirs = merge_dirs[start:end]

        print(f"Sending dirs to proc {i}")
        comm.send(proc_dirs, dest=i + 1)

    print(f"Master waiting for results")
    results = []
    for i in range(num_procs - 1):
        results += comm.recv(source=MPI.ANY_SOURCE, status=stat)
        print(f"Master got results from {stat.source}")

    reporter.write_results(results, "results.csv")


def worker(merge_commands):
    assert merge_base_dir is not None
    print(f"Hello world, I am worker {rank}")

    dirs = comm.recv(source=MASTER_RANK)
    print(f"Proc {rank} got {len(dirs)} merge dirs from master")

    results = []

    num_done = 0
    tot = len(dirs)

    for merge_evaluation in gather.run_and_evaluate(dirs, merge_commands):
        results.append(merge_evaluation)
        num_done += 1
        print(f"Proc {rank} progress: {num_done}/{tot}")

    print(f"Proc {rank} sending results to master")
    comm.send(results, dest=MASTER_RANK)


def main(args: argparse.Namespace):
    global merge_base_dir
    merge_base_dir = pathlib.Path("merge_directory").absolute()

    if rank == MASTER_RANK:
        master(args.repo, args.github_user, args.num_merges)
    else:
        worker(args.merge_commands)
