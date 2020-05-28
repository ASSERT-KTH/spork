"""Process utility functions."""
import subprocess
import signal
import os
import sys

from typing import List, Tuple

import daiquiri

LOGGER = daiquiri.getLogger(__name__)


def run_with_sigkill_timeout(
    cmd: List[str], timeout: int, **kwargs
) -> Tuple[subprocess.Popen, str]:
    """Run a process with a timeout, and issue a SIGKILL to the process GROUP
    if it times out. This is a necessary precaution for some processes that
    have a tendency to get locked in infinite loops.

    Returns:
        A tuple on the form (process, timed_out), where timed_out is True iff
        the process timed out.
    """
    # run the process in a new process group, so we can forcibly kill the entire
    # group without also killing the the python interpreter
    proc = subprocess.Popen(
        cmd,
        preexec_fn=os.setsid,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        **kwargs
    )
    try:
        out, _ = proc.communicate(timeout=timeout)
        return proc, out.decode(sys.getdefaultencoding())
    except subprocess.TimeoutExpired:
        LOGGER.exception("Timeout!")
        # forcibly kill the process and all children
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        raise
