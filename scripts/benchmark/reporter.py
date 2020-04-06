"""Reporting module that can write results to a CSV file."""
import csv
import sys
import pathlib

from typing import List, Iterable

from . import gather

def write_results(results: Iterable[gather.MergeEvaluation], dst: str) -> None:
    content = [
        list(gather.MergeEvaluation._fields),
        *[[str(v) for v in res] for res in results],
    ]
    formatted_content = _format_for_csv(content)

    with open(dst, mode="w", encoding=sys.getdefaultencoding()) as file:
        writer = csv.writer(file, delimiter=",")
        writer.writerows(formatted_content)


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


