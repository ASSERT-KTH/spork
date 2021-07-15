import pathlib
import math
import itertools
import pandas as pd
import scipy.stats
import seaborn as sns
import matplotlib.pyplot as plt
import numpy as np
import pingouin as pg

THIS_DIR = pathlib.Path(__file__).parent

sns.set(font_scale=3, palette="pastel", style="ticks", context="paper")

FILE_MERGE_EVALS = pd.read_csv(THIS_DIR / "results" / "file_merge_evaluations.csv")

# merge directories in which JDime or Spork (or both) exhibit fails/conflicts
FAIL_MERGE_DIRS = set(FILE_MERGE_EVALS.query("outcome == 'fail' or outcome == 'timeout'").merge_dir.unique())
CONFLICT_MERGE_DIRS = set(
    FILE_MERGE_EVALS.query("outcome == 'conflict'").merge_dir.unique()
)


def plot_git_diff_sizes():
    filtered_file_merges = FILE_MERGE_EVALS[
        ~FILE_MERGE_EVALS.merge_dir.isin(FAIL_MERGE_DIRS | CONFLICT_MERGE_DIRS)
    ]
    aligned_file_merges = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="line_diff_size",
        frame=filtered_file_merges,
    )
    bins = [0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650]
    histogram(
        spork_values=aligned_file_merges.spork_line_diff_size,
        jdime_values=aligned_file_merges.jdime_line_diff_size,
        jdimeimproved_values=aligned_file_merges.jdimeimproved_line_diff_size,
        bins=bins,
        xlabel="GitDiff size (insertions + deletions)",
    )


def plot_runtimes():
    bins = [0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5]
    non_fail_merges = FILE_MERGE_EVALS[
        ~FILE_MERGE_EVALS.merge_dir.isin(FAIL_MERGE_DIRS)
    ]
    aligned_file_merges = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="runtime",
        frame=non_fail_merges,
    )
    histogram(
        spork_values=aligned_file_merges.spork_runtime,
        jdime_values=aligned_file_merges.jdime_runtime,
        jdimeimproved_values=aligned_file_merges.jdimeimproved_runtime,
        bins=bins,
        xlabel="Running time (seconds)",
    )


def plot_mean_conflict_sizes():
    bins = [0, 2, 4, 6, 8, 10, 12, 14, 16, 18]
    aligned_mean_conflict_sizes = get_aligned_mean_conflict_sizes().query(
        "spork_avg_size > 0 or jdime_avg_size > 0 or jdimeimproved_avg_size > 0"
    )
    histogram(
        spork_values=aligned_mean_conflict_sizes.spork_avg_size,
        jdime_values=aligned_mean_conflict_sizes.jdime_avg_size,
        jdimeimproved_values=aligned_mean_conflict_sizes.jdimeimproved_avg_size,
        bins=bins,
        xlabel="Mean conflict hunk size per file",
    )


def plot_conflict_hunk_quantities():
    bins = [0, 1, 2, 3, 4, 5]
    non_fail_conflict_dirs = CONFLICT_MERGE_DIRS - FAIL_MERGE_DIRS
    conflicts = FILE_MERGE_EVALS[
        FILE_MERGE_EVALS.merge_dir.isin(non_fail_conflict_dirs)
    ]
    aligned_conflicts = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="num_conflicts",
        frame=conflicts,
    )
    histogram(
        spork_values=aligned_conflicts.spork_num_conflicts,
        jdime_values=aligned_conflicts.jdime_num_conflicts,
        jdimeimproved_values=aligned_conflicts.jdimeimproved_num_conflicts,
        bins=bins,
        xlabel="Amount of conflict hunks per file",
    )

def plot_char_diff_size():
    bins = [0, 1000, 2000, 3000, 4000, 5000, 6000]
    filtered_file_merges = FILE_MERGE_EVALS[
        ~FILE_MERGE_EVALS.merge_dir.isin(FAIL_MERGE_DIRS | CONFLICT_MERGE_DIRS)
    ]
    aligned_file_merges = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="char_diff_size",
        frame=filtered_file_merges,
    )
    histogram(
        spork_values=aligned_file_merges.spork_char_diff_size,
        jdime_values=aligned_file_merges.jdime_char_diff_size,
        jdimeimproved_values=aligned_file_merges.jdimeimproved_char_diff_size,
        bins=bins,
        xlabel="Character diff size",
    )




def plot_char_diff_ratio():
    bins = [.75, .8, .85, .9, .95, 1]
    filtered_file_merges = FILE_MERGE_EVALS[
        ~FILE_MERGE_EVALS.merge_dir.isin(FAIL_MERGE_DIRS | CONFLICT_MERGE_DIRS)
    ]
    aligned_file_merges = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="char_diff_ratio",
        frame=filtered_file_merges,
    )
    histogram(
        spork_values=aligned_file_merges.spork_char_diff_ratio,
        jdime_values=aligned_file_merges.jdime_char_diff_ratio,
        jdimeimproved_values=aligned_file_merges.jdimeimproved_char_diff_ratio,
        bins=bins,
        xlabel="Character diff ratio",
    )

def histogram(
    spork_values, jdime_values, jdimeimproved_values, bins, xlabel, ylabel="Frequency"
):
    smallest_value = min(0, min(itertools.chain(spork_values, jdime_values, jdimeimproved_values)))
    largest_value = max(itertools.chain(spork_values, jdime_values, jdimeimproved_values))

    has_lower_bound = smallest_value >= bins[0]
    has_upper_bound = largest_value < bins[-1]

    def get_ticklabel(bin_value):
        if bin_value == bins[0] and not has_lower_bound:
            return str(int(math.floor(smallest_value)))
        elif bin_value == bins[-1] and not has_upper_bound:
            return str(int(math.ceil(largest_value)))
        else:
            return str(bin_value)

    # limits values to be in the range of bins, but does not remove any values
    clipped_spork_values = np.clip(spork_values, bins[0], bins[-1])
    clipped_jdime_values = np.clip(jdime_values, bins[0], bins[-1])
    clipped_jdimeimproved_values = np.clip(jdimeimproved_values, bins[0], bins[-1])

    _, ax = plt.subplots()
    plt.hist(
        [clipped_spork_values, clipped_jdime_values, clipped_jdimeimproved_values],
        bins=bins,
    )
    set_hatches(ax)

    handles = [ax.patches[0], ax.patches[len(ax.patches) // 2], ax.patches[-1]]
    labels = ["Spork", "JDime", "JDimeImproved"]
    plt.legend(handles, labels)
    plt.xticks(bins)
    plt.tick_params(axis="both", which="major", labelsize=20)

    ax.set_ylabel(ylabel)
    ax.set_xlabel(xlabel)

    ticklabels = list(map(get_ticklabel, bins))
    ax.set_xticklabels(ticklabels)

    print(spork_values.describe())
    print(jdime_values.describe())
    print(pg.wilcoxon(spork_values, jdime_values, tail="two-sided"))

    plt.show()


def set_hatches(ax):
    for patch in ax.patches[len(ax.patches) // 3 :]:
        patch.set_hatch("/")
    for patch in ax.patches[int(2/3 * len(ax.patches)) :]:
        patch.set_hatch("x")


def get_aligned_results(
    merge_id_column: str,
    data_column: str,
    tool_column: str,
    frame: pd.DataFrame,
) -> pd.DataFrame:
    tools = frame[tool_column].unique()
    assert len(tools) > 0

    aligned_frame = frame[[merge_id_column]].drop_duplicates()
    for tool in tools:
        tool_frame = frame.query(f"{tool_column} == '{tool}'")[
            [merge_id_column, data_column]
        ].rename(columns={data_column: f"{tool}_{data_column}"})
        aligned_frame = aligned_frame.merge(tool_frame, on=merge_id_column)

    return aligned_frame


def get_aligned_mean_conflict_sizes():
    non_fail_conflict_dirs = CONFLICT_MERGE_DIRS - FAIL_MERGE_DIRS
    non_fail_conflict_merges = FILE_MERGE_EVALS[
        FILE_MERGE_EVALS.merge_dir.isin(non_fail_conflict_dirs)
    ]

    spork_conflicts = mean_conflict_sizes(non_fail_conflict_merges, "spork")
    jdime_conflicts = mean_conflict_sizes(non_fail_conflict_merges, "jdime")
    jdimeimproved_conflicts = mean_conflict_sizes(non_fail_conflict_merges, "jdimeimproved")

    return spork_conflicts.merge(jdime_conflicts, on="merge_dir").merge(jdimeimproved_conflicts, on="merge_dir")


def mean_conflict_sizes(frame: pd.DataFrame, tool: str):
    cmd_mean_conflict_sizes = frame[frame.merge_cmd == tool].merge_dir.to_frame()
    cmd_mean_conflict_sizes[f"{tool}_avg_size"] = frame[frame.merge_cmd == tool].apply(
        avg_chunk_size, axis=1
    )
    return cmd_mean_conflict_sizes


def avg_chunk_size(row):
    if row.num_conflicts:
        return row.conflict_size / row.num_conflicts
    return 0


if __name__ == "__main__":
    plot_conflict_hunk_quantities()
    plot_mean_conflict_sizes()
    plot_runtimes()
    plot_git_diff_sizes()
    plot_char_diff_size()
    plot_char_diff_ratio()
