import pathlib
import pandas as pd
import scipy.stats
import seaborn as sns
import matplotlib.pyplot as plt
import numpy as np

THIS_DIR = pathlib.Path(__file__).parent

sns.set(font_scale=3, palette="pastel", style="ticks", context="paper")

FILE_MERGES = pd.read_csv(THIS_DIR / "results" / "file_merge_results.csv")
RUNTIMES = pd.read_csv(THIS_DIR / "results" / "runtimes.csv")

# merge directories in which JDime or Spork (or both) exhibit fails/conflicts
FAIL_MERGE_DIRS = set(FILE_MERGES.query("outcome == 'fail'").merge_dir.unique())
CONFLICT_MERGE_DIRS = set(FILE_MERGES.query("outcome == 'conflict'").merge_dir.unique())


def plot_git_diff_sizes():
    filtered_file_merges = FILE_MERGES[
        ~FILE_MERGES.merge_dir.isin(FAIL_MERGE_DIRS | CONFLICT_MERGE_DIRS)
    ]
    aligned_file_merges = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="git_diff_size",
        frame=filtered_file_merges,
    )
    bins = [0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650]
    histogram(
        spork_values=aligned_file_merges.spork_git_diff_size,
        jdime_values=aligned_file_merges.jdime_git_diff_size,
        bins=bins,
        xlabel="GitDiff size (insertions + deletions)",
    )


def plot_runtimes():
    bins = [0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5]
    histogram(
        spork_values=RUNTIMES.spork_runtime,
        jdime_values=RUNTIMES.jdime_runtime,
        bins=bins,
        xlabel="Running time (seconds)",
    )


def plot_mean_conflict_sizes():
    bins = [0, 2, 4, 6, 8, 10, 12, 14, 16, 18]
    aligned_mean_conflict_sizes = get_aligned_mean_conflict_sizes().query(
        "spork_avg_size > 0 and jdime_avg_size > 0"
    )
    histogram(
        spork_values=aligned_mean_conflict_sizes.spork_avg_size,
        jdime_values=aligned_mean_conflict_sizes.jdime_avg_size,
        bins=bins,
        xlabel="Mean conflict hunk size per file",
    )


def plot_conflict_hunk_quantities():
    bins = [0, 1, 2, 3, 4, 5]
    non_fail_conflict_dirs = CONFLICT_MERGE_DIRS - FAIL_MERGE_DIRS
    conflicts = FILE_MERGES[FILE_MERGES.merge_dir.isin(non_fail_conflict_dirs)]
    aligned_conflicts = get_aligned_results(
        merge_id_column="merge_dir",
        tool_column="merge_cmd",
        data_column="num_conflicts",
        frame=conflicts,
    )
    histogram(
        spork_values=aligned_conflicts.spork_num_conflicts,
        jdime_values=aligned_conflicts.jdime_num_conflicts,
        bins=bins,
        xlabel="Amount of conflict hunks per file",
    )


def histogram(spork_values, jdime_values, bins, xlabel, ylabel="Frequency"):
    # limits values to be in the range of bins, but does not remove any values
    clipped_spork_values = np.clip(spork_values, bins[0], bins[-1])
    clipped_jdime_values = np.clip(jdime_values, bins[0], bins[-1])

    _, ax = plt.subplots()
    plt.hist([clipped_spork_values, clipped_jdime_values], bins=bins)
    set_hatches(ax)

    handles = [ax.patches[0], ax.patches[-1]]
    labels = ["Spork", "JDime"]
    plt.legend(handles, labels)
    plt.xticks(bins)
    plt.tick_params(axis="both", which="major", labelsize=20)

    ax.set_ylabel(ylabel)
    ax.set_xlabel(xlabel)

    ticklabels = [str(k) if k != bins[-1] else "∞" for k in bins]
    ax.set_xticklabels(ticklabels)

    print(spork_values.describe())
    print(jdime_values.describe())
    print(wilcoxon(spork_data=spork_values, jdime_data=jdime_values))

    plt.show()


def set_hatches(ax):
    for patch in ax.patches[len(ax.patches) // 2 :]:
        patch.set_hatch("/")


def read_results(merge_dirs: pathlib.Path, results_file_name: str) -> pd.DataFrame:
    frames = []
    for dir_ in merge_dirs.iterdir():
        if not dir_.is_dir():
            continue
        proj_name = dir_.name.replace("_", "/")
        try:
            frame = pd.read_csv(str(dir_ / results_file_name), skipinitialspace=True)
        except FileNotFoundError:
            continue
        frame["project"] = proj_name
        cols = frame.columns.to_list()
        cols = cols[-1:] + cols[:-1]
        frame = frame[cols]
        frames.append(frame)
    return pd.concat(frames)


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
    non_fail_conflict_merges = FILE_MERGES[
        FILE_MERGES.merge_dir.isin(non_fail_conflict_dirs)
    ]

    spork_conflicts = mean_conflict_sizes(non_fail_conflict_merges, "spork")
    jdime_conflicts = mean_conflict_sizes(non_fail_conflict_merges, "jdime")

    return spork_conflicts.merge(jdime_conflicts, on="merge_dir")


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


def wilcoxon(spork_data, jdime_data) -> scipy.stats.morestats.WilcoxonResult:
    return scipy.stats.wilcoxon(
        spork_data, jdime_data, alternative="two-sided", zero_method="pratt"
    )


if __name__ == "__main__":
    plot_conflict_hunk_quantities()
    plot_mean_conflict_sizes()
    plot_runtimes()
    plot_git_diff_sizes()
