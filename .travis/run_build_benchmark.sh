#! /bin/bash

cd "$TRAVIS_BUILD_DIR"/scripts || exit

echo "Setting JAVA_HOME"
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
echo $JAVA_HOME

python3 -m benchmark.cli run-git-merges \
  -r spoon \
  -g inria \
  --merge-commits buildable_spoon_merges.txt \
  --merge-drivers spork \
  --eval-dir evaluation-directory \
  --output results.csv

cat results.csv

diff expected_build_results.csv results.csv
exit $?
