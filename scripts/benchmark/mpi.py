"""Module for running benchmarks in parallel with MPI"""
import pathlib

from typing import List

import daiquiri

LOGGER = daiquiri.getLogger(__name__)

MPI_ENABLED = False
try:
    from mpi4py import MPI

    COMM = MPI.COMM_WORLD
    NUM_PROCS = COMM.Get_size()
    RANK = COMM.Get_rank()
    STAT = MPI.Status()

    MASTER_RANK = 0
    NUM_WORKERS = NUM_PROCS - 1


    MPI_ENABLED = True
except ModuleNotFoundError:
    LOGGER.warning("MPI not installed, will not be able to run in MPI-mode")



def master(merge_dirs: List[pathlib.Path]):
    print("Hello world, I am the master!")

    dirs_per_proc = int(len(merge_dirs) / NUM_WORKERS)
    range_starts = [i * dirs_per_proc for i in range(NUM_WORKERS)]

    for i in range(NUM_PROCS - 1):
        start = range_starts[i]
        end = range_starts[i + 1] if i < len(range_starts) - 1 else len(merge_dirs)
        proc_dirs = merge_dirs[start:end]

        print(f"Sending dirs to proc {i}")
        COMM.send(proc_dirs, dest=i + 1)

    print(f"Master waiting for results")
    results = []
    for i in range(NUM_PROCS - 1):
        results += COMM.recv(source=MPI.ANY_SOURCE, status=STAT)
        print(f"Master got results from {STAT.source}")

    return results


def worker(evaluation_function, num_merge_commands):
    print(f"Hello world, I am worker {RANK}")

    dirs = COMM.recv(source=MASTER_RANK)
    print(f"Proc {RANK} got {len(dirs)} jobs from master")

    results = []

    num_done = 0
    tot = len(dirs) * num_merge_commands

    for merge_evaluation in evaluation_function(dirs):
        results.append(merge_evaluation)
        num_done += 1
        print(f"Proc {RANK} progress: {num_done}/{tot}")

    print(f"Proc {RANK} sending results to master")
    COMM.send(results, dest=MASTER_RANK)
