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
    LOGGER.info("Master starting ...")

    dirs_per_proc = int(len(merge_dirs) / NUM_WORKERS)
    range_starts = [i * dirs_per_proc for i in range(NUM_WORKERS)]

    for i in range(NUM_PROCS - 1):
        start = range_starts[i]
        end = range_starts[i + 1] if i < len(range_starts) - 1 else len(merge_dirs)
        proc_dirs = merge_dirs[start:end]

        LOGGER.info(f"Master sending jobs to proc {i}")
        COMM.send(proc_dirs, dest=i + 1)

    LOGGER.info(f"Master waiting for results")
    results = []
    for i in range(NUM_PROCS - 1):
        results += COMM.recv(source=MPI.ANY_SOURCE, status=STAT)
        LOGGER.info(f"Master got results from {STAT.source}")

    LOGGER.info("Master exiting")
    return results


def worker(job_func, reps_per_job):
    LOGGER.info(f"Worker {RANK} starting ...")

    jobs = COMM.recv(source=MASTER_RANK)
    LOGGER.info(f"Worker {RANK} got {len(jobs)} jobs from master")

    results = []

    num_done = 0
    tot = len(jobs) * reps_per_job

    for result in job_func(jobs):
        results.append(result)
        num_done += 1
        LOGGER.info(f"Worker {RANK} progress: {num_done}/{tot}")

    LOGGER.info(f"Worker {RANK} sending results to master")
    COMM.send(results, dest=MASTER_RANK)
