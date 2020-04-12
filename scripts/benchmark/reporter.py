"""Reporting module that can write results to a CSV file."""
import csv
import sys
import pathlib

from typing import List, Iterable

from . import evaluate


def write_results(results: Iterable[evaluate.MergeEvaluation], dst: str) -> None:
    content = [
        list(evaluate.MergeEvaluation._fields),
        *[[str(v) for v in res] for res in results],
    ]
    formatted_content = _format_for_csv(content)

    with open(dst, mode="w", encoding=sys.getdefaultencoding()) as file:
        writer = csv.writer(file, delimiter=",")
        writer.writerows(formatted_content)


def read_results(results_path: pathlib.Path) -> List[evaluate.MergeEvaluation]:
    with open(str(results_path), mode="r") as file:
        reader = csv.reader(file, dialect=_IgnoreWhitespaceDialect())
        hdrs = list(next(reader))
        expected_hdrs = list(evaluate.MergeEvaluation._fields)

        if hdrs != expected_hdrs:
            raise ValueError(
                "provided CSV file has wrong headers, expected "
                f"{expected_hdrs}, got {hdrs}"
            )

        return [
            evaluate.MergeEvaluation(*[_parse_value(v) for v in line]) for line in reader
        ]


def _parse_value(v: str):
    if "/" in v:  # this is a path
        return pathlib.Path(v)

    for conv_func in [int, float]:
        try:
            return conv_func(v)
        except ValueError:
            pass
    return v


def _format_for_csv(results: List[List[str]]) -> List[List[str]]:
    column_widths = _largest_cells(results)
    return [
        [cell.rjust(column_widths[i]) for i, cell in enumerate(row)] for row in results
    ]


def _largest_cells(rows):
    """Return a list with the widths of the largest cell of each column."""
    transpose = list(zip(*rows))
    widths = map(lambda row: map(len, row), transpose)
    return list(map(max, widths))


class _IgnoreWhitespaceDialect(csv.unix_dialect):
    skipinitialspace = True
