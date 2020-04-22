"""Reporting module that can write results to a CSV file."""
import csv
import sys
import pathlib
import dataclasses

from typing import List, Iterable, Callable, TypeVar, Any

from . import evaluate
from . import gitutils
from . import run
from . import containers as conts


T = TypeVar("T")


def write_csv(data: Iterable[T], container: T, dst: pathlib.Path) -> None:
    if not dataclasses.is_dataclass(container):
        raise TypeError(f"{container} is not a dataclass")

    _write_csv(
        headers=[field.name for field in dataclasses.fields(container)],
        body=[[str(v) for v in dataclasses.astuple(res)] for res in data],
        dst=dst,
    )


def read_csv(csv_file: pathlib.Path, container: T) -> List[T]:
    if not dataclasses.is_dataclass(container):
        raise TypeError(f"{container} is not a dataclass")

    return _read_csv(
        container=container,
        csv_file=csv_file,
    )

def _read_csv(
    container: Callable[..., T], csv_file: pathlib.Path
) -> Callable[..., T]:
    fields = dataclasses.fields(container)
    expected_headers = [field.name for field in fields]
    with open(str(csv_file), mode="r") as file:
        reader = csv.reader(file, dialect=_IgnoreWhitespaceDialect())
        headers = list(next(reader))

        if headers != expected_headers:
            raise ValueError(
                "provided CSV file has wrong headers, expected "
                f"{expected_headers}, got {headers}"
            )

        def instantiate_container(line: List[str]) -> container:
            assert len(line) == len(fields)
            kwargs = {field.name: field.type(v) for v, field in zip(line, fields)}
            return container(**kwargs)

        return [instantiate_container(line) for line in reader]


def _write_csv(headers: List[str], body: List[List[str]], dst: str):
    sorted_body = sorted(body)
    formatted_content = _format_for_csv([headers, *sorted_body])

    with open(dst, mode="w", encoding=sys.getdefaultencoding()) as file:
        writer = csv.writer(file, delimiter=",")
        writer.writerows(formatted_content)


def _format_for_csv(results: List[List[str]]) -> List[List[str]]:
    column_widths = [largest + 1 for largest in _largest_cells(results)]
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
