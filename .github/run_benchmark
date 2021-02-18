#! /bin/bash

cd "$GITHUB_WORKSPACE"/scripts || exit

python3 -m benchmark.cli run-file-merge-compare -r rxjava -g reactivex --compare expected_results.csv --merge-commands spork --output results.csv
bench_returncode=$?

cat results.csv

exit $bench_returncode
