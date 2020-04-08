"""Module for analyzing evaluation results."""
import dataclasses
import pathlib
import functools
from typing import List, Iterable, Tuple, Any

import daiquiri
from . import gather
from . import reporter
from . import run

from .gather import EvalAttrName

LOGGER = daiquiri.getLogger(__name__)


@dataclasses.dataclass(frozen=True)
class Evaluations:
    """A class to store and analyze evaluations. The evaluations are always
    sorted by merge directory.
    """

    evaluations: Tuple[gather.MergeEvaluation]

    def __init__(self, evaluations: Iterable[gather.MergeEvaluation]):
        sorted_evals = tuple(sorted(evaluations, key=lambda e: e.merge_dir))
        object.__setattr__(self, "evaluations", sorted_evals)

    @staticmethod
    def _check_valid_attr(attr_name: str) -> None:
        if not attr_name in [e.value for e in EvalAttrName]:
            raise ValueError(
                f"no attribute {attr_name} in {gather.MergeEvaluation._fields}"
            )

    @staticmethod
    def from_path(path: pathlib.Path) -> "Evaluations":
        return Evaluations(reporter.read_results(path))

    def log_diffs(self, ref: "Evaluations") -> None:
        """Log status differences for the evaluations, per directory."""
        for self_eval, ref_eval in zip(self.evaluations, ref.evaluations):
            if self_eval.merge_dir != ref_eval.merge_dir:
                raise ValueError(
                    f"mismatching merge directories: '{self_eval.merge_dir}' and '{ref_eval.merge_dir}'"
                )

            for attr_name in gather.NUMERICAL_EVAL_ATTR_NAMES + (EvalAttrName.outcome.value,):
                self_val = getattr(self_eval, attr_name)
                ref_val = getattr(ref_eval, attr_name)
                self_as_good = _compare(attr_name, self_val, ref_val)
                log_func = LOGGER.warning if not self_as_good else LOGGER.info
                log_func(
                    f"{self_eval.merge_dir}###{attr_name}: was={ref_val}, now={self_val}"
                )

    @functools.lru_cache
    def mean(self, attr_name: str) -> float:
        """Compute the mean of the provided attribute."""
        return sum(self.extract(attr_name)) / len(self.evaluations)

    @functools.lru_cache
    def extract(self, attr_name: str) -> Iterable:
        """Extract all attributes of the provided name."""
        self._check_valid_attr(attr_name)
        return (getattr(e, attr_name) for e in self.evaluations)

    def num_equal(self, attr_name: str, value: Any) -> int:
        """Count the amount of this attribute that are equal to the provided value."""
        return len([v for v in self.extract(attr_name) if v == value])

    def at_least_as_good_as(self, ref: "Evaluations") -> bool:
        """Determine if self is at least as good as ref in all categories."""
        if not isinstance(ref, Evaluations):
            raise TypeError(
                f"Can't compare {self.__class__.__name__} to {ref.__class__.__name__}"
            )

        return all(self._at_least_as_good_as(ref))

    def _at_least_as_good_as(self, ref):
        self_fails = self.num_equal(EvalAttrName.outcome.value, run.MergeOutcome.FAIL)
        ref_fails = self.num_equal(EvalAttrName.outcome.value, run.MergeOutcome.FAIL)

        if self_fails > ref_fails:
            LOGGER.warning(f"More fails: was={ref_fails}, now={self_fails}")

        for attr_name in gather.NUMERICAL_EVAL_ATTR_NAMES:
            self_val = self.mean(attr_name)
            ref_val = ref.mean(attr_name)

            as_good_or_better = _compare(attr_name, self_val, ref_val)

            if not as_good_or_better:
                LOGGER.warning(f"Deteriorated score for {attr_name}: was={ref_val}, now={self_val}")

            yield as_good_or_better

def _compare(attr_name: str, compare_val, ref_val) -> bool:
    if attr_name == EvalAttrName.runtime.value:
        return not _significantly_greater_than(compare_val, ref_val, tol_fraction=1.2)
    elif attr_name == EvalAttrName.outcome.value:
        return compare_val == ref_val or compare_val != run.MergeOutcome.FAIL
    else:
        return compare_val <= ref_val

def _significantly_greater_than(
    compare: float, reference: float, min_value: float = 2.0, tol_fraction: float = 1.2,
) -> bool:
    """Return true if compare is significantly larger than reference, and at least one is larger than min_value."""
    if compare < min_value and reference < min_value:
        return True
    return compare / reference > tol_fraction
