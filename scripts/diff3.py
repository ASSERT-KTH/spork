import argparse
import subprocess
import sys
import pathlib


def main():
    parser = argparse.ArgumentParser("diff3 wrapper")

    parser.add_argument("left", type=pathlib.Path, help="left revision")
    parser.add_argument("base", type=pathlib.Path, help="base revision")
    parser.add_argument("right", type=pathlib.Path, help="right revision")
    parser.add_argument(
        "-o", "--output", type=pathlib.Path, help="output file", required=True
    )

    args = parser.parse_args(sys.argv[1:])

    proc = subprocess.run(
        f"diff3 -m -A {args.left} {args.base} {args.right}".split(), capture_output=True
    )

    output: pathlib.Path = args.output
    output.touch()
    output.write_bytes(proc.stdout)

    sys.exit(proc.returncode)


if __name__ == "__main__":
    main()
