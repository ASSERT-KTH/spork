"""Module for analyzing evaluation results."""
import dataclasses
import pathlib
import functools
import itertools

from typing import List, Iterable, Tuple, Any, TypeVar, Mapping

import daiquiri

from . import evaluate
from . import reporter
from . import run
from . import containers as conts

LOGGER = daiquiri.getLogger(__name__)

T = TypeVar("T")


@dataclasses.dataclass(frozen=True)
class Evaluations:
    """A class to store and analyze evaluations. The evaluations are always
    sorted by merge directory.
    """

    data: Tuple[T]
    container: T

    def __init__(self, data: Iterable[T], container: T):
        sorted_evals = tuple(sorted(data))
        object.__setattr__(self, "data", sorted_evals)
        object.__setattr__(self, "container", container)

    def _check_valid_attr(self, attr_name: str) -> None:
        if not attr_name in [
            field.name for field in dataclasses.fields(self.container)
        ]:
            raise ValueError(f"no attribute {attr_name} in {self.container.__name__}")

    @staticmethod
    def from_path(path: pathlib.Path, container: T) -> "Evaluations":
        return Evaluations(
            reporter.read_csv(csv_file=path, container=container), container
        )

    def mean(self, attr_name: str) -> float:
        """Compute the mean of the provided attribute."""
        return sum(self.extract(attr_name)) / len(self.data)

    def extract(self, attr_name: str) -> Iterable:
        """Extract all attributes of the provided name."""
        self._check_valid_attr(attr_name)
        return (getattr(e, attr_name) for e in self.data)

    def num_equal(self, attr_name: str, value: Any) -> int:
        """Count the amount of this attribute that are equal to the provided value."""
        return len([v for v in self.extract(attr_name) if v == value])

    def log_diffs(self, ref: "Evaluations") -> None:
        """Log status differences for the evaluations, per directory."""
        numerical_attrs = [
            field.name
            for field in dataclasses.fields(self.container)
            if field.type in (int, float)
        ]
        for self_eval, ref_eval in zip(self.data, ref.data):
            for attr_name in numerical_attrs:
                self_val = getattr(self_eval, attr_name)
                ref_val = getattr(ref_eval, attr_name)
                self_as_good = _compare(attr_name, self_val, ref_val)
                log_func = LOGGER.warning if not self_as_good else LOGGER.info
                log_func(
                    f"{self_eval.merge_dir}###{attr_name}: was={ref_val}, now={self_val}"
                )

    def at_least_as_good_as(self, ref: "Evaluations") -> bool:
        """Determine if self is at least as good as ref in all categories."""
        if not issubclass(self.container, conts.MergeEvaluation):
            raise f"Can only make comparisons for {conts.MergeEvaluation.__name__}"

        if not isinstance(ref, Evaluations):
            raise TypeError(
                f"Can't compare {self.__class__.__name__} to {ref.__class__.__name__}"
            )

        return all(self._at_least_as_good_as(ref))

    def _at_least_as_good_as(self, ref):
        self_fails = self.num_equal("outcome", conts.MergeOutcome.FAIL)
        ref_fails = self.num_equal("outcome", conts.MergeOutcome.FAIL)

        if self_fails > ref_fails:
            LOGGER.warning(f"More fails: was={ref_fails}, now={self_fails}")

        for attr_name in [
            field.name
            for field in dataclasses.fields(self.container)
            if field.type in [int, float]
        ]:
            self_val = self.mean(attr_name)
            ref_val = ref.mean(attr_name)

            as_good_or_better = _compare(attr_name, self_val, ref_val)

            if not as_good_or_better:
                LOGGER.warning(
                    f"Deteriorated score for {attr_name}: was={ref_val}, now={self_val}"
                )

            yield as_good_or_better


def analyze_merge_evaluations(
    merge_evaluations: List[conts.MergeEvaluation],
    blob_line_counts: Mapping[str, int],
    blob_node_counts: Mapping[str, int],
) -> List[conts.MergeEvaluationStatistics]:
    stats = []

    merge_evaluations.sort(key=lambda me: me.merge_cmd)
    for merge_cmd, evals in itertools.groupby(
        merge_evaluations, key=lambda me: me.merge_cmd
    ):
        print()
        print(f"Merge command: {merge_cmd}")
        evals = list(evals)
        git_diff_accuracy, git_diff_magnitude = _calculate_averages(
            evals, blob_line_counts, "git_diff_size", normalized=False
        )
        git_diff_accuracy_norm, git_diff_magnitude_norm = _calculate_averages(
            evals, blob_line_counts, "git_diff_size", normalized=True
        )
        gumtree_diff_accuracy, gumtree_diff_magnitude = _calculate_averages(
            evals, blob_node_counts, "gumtree_diff_size", normalized=False
        )
        gumtree_diff_accuracy_norm, gumtree_diff_magnitude_norm = _calculate_averages(
            evals, blob_node_counts, "gumtree_diff_size", normalized=True
        )
        stats.append(
            conts.MergeEvaluationStatistics(
                "rxjava",
                merge_cmd,
                git_diff_avg_acc=git_diff_accuracy,
                git_diff_avg_magn=git_diff_magnitude,
                git_diff_avg_acc_norm=git_diff_accuracy_norm,
                git_diff_avg_magn_norm=git_diff_magnitude_norm,
                gumtree_diff_avg_acc=gumtree_diff_accuracy,
                gumtree_diff_avg_magn=gumtree_diff_magnitude,
                gumtree_diff_avg_acc_norm=gumtree_diff_accuracy_norm,
                gumtree_diff_avg_magn_norm=gumtree_diff_magnitude_norm,
                num_file_merges=len(evals),
                num_success=len(
                    [me for me in evals if me.outcome == conts.MergeOutcome.SUCCESS]
                ),
                num_fail=len(
                    [me for me in evals if me.outcome == conts.MergeOutcome.FAIL]
                ),
                num_conflict=len(
                    [me for me in evals if me.outcome == conts.MergeOutcome.CONFLICT]
                ),
            )
        )

    return stats


def _calculate_averages(
    evals: List[conts.MergeEvaluation],
    blob_sizes: Mapping[str, int],
    diff_attr: str,
    normalized: bool,
):
    def _create_attr_name(attr):
        return attr + ("_norm" if normalized else "")

    expected_blob_attr_name = _create_attr_name("expected_blob")
    replayed_blob_attr_name = _create_attr_name("replayed_blob")
    diff_attr_name = _create_attr_name(diff_attr)

    accuracies = []
    magnitudes = []
    for merge_eval in evals:
        expected_blob_sha = getattr(merge_eval, expected_blob_attr_name)
        replayed_blob_sha = getattr(merge_eval, replayed_blob_attr_name)

        expected_size = blob_sizes[expected_blob_sha]
        replayed_size = blob_sizes[replayed_blob_sha]
        diff_size = getattr(merge_eval, diff_attr_name)

        if merge_eval.outcome == conts.MergeOutcome.SUCCESS:
            magnitudes.append(diff_size)
            accuracies.append(
                accuracy(
                    expected_size=expected_size,
                    replayed_size=replayed_size,
                    diff_size=diff_size,
                )
            )

    non_successful = len(evals) - len(accuracies)
    punish_accuracy = min(accuracies) * non_successful

    avg_accuracy = float(sum(accuracies) + punish_accuracy) / len(evals)
    avg_magnitude = float(sum(magnitudes)) / len(magnitudes)
    print(f"{diff_attr_name} magnitude avg: {avg_magnitude}")
    print(f"{diff_attr_name} magnitude max: {max(magnitudes)}")
    print(f"{diff_attr_name} magnitude min: {min(magnitudes)}")
    print(f"{diff_attr_name} accuracy avg: {avg_accuracy}")
    print(f"{diff_attr_name} accuracy max: {max(accuracies)}")
    print(f"{diff_attr_name} accuracy min: {min(accuracies)}")
    return avg_accuracy, avg_magnitude


def accuracy(expected_size: int, replayed_size: int, diff_size: int):
    """Calculate the accuracy of a merge tool. The size can be any metric
    so long all values are the same metric, and the maximum possible diff size
    is equal to the expected_size plus the replayed_size.

    Args:
        expected_size: The size of the expected revision.
        replayed_size: The size of the replayed revision.
        diff_size: The size of the diff.
    Returns:
        The accuracy of this merge.
    """
    return 1 - float(diff_size) / (expected_size + replayed_size)


def _compare(attr_name: str, compare_val, ref_val) -> bool:
    if attr_name == "runtime":
        return not _significantly_greater_than(compare_val, ref_val, tol_fraction=1.2)
    elif attr_name == "outcome":
        return compare_val == ref_val or compare_val != conts.MergeOutcome.FAIL
    else:
        return compare_val <= ref_val


def _significantly_greater_than(
    compare: float, reference: float, min_value: float = 2.0, tol_fraction: float = 1.2,
) -> bool:
    """Return true if compare is significantly larger than reference, and at least one is larger than min_value."""
    if compare < min_value and reference < min_value:
        return True
    return compare / reference > tol_fraction
