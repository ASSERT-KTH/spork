#! /bin/bash

echo "Compiling spork"
mvn clean compile assembly:single
spork_jar_path="$PWD/$(ls target/spork*.jar)"

echo "Creating spork executable"
echo "#! /bin/bash" > spork
echo "java -jar $spork_jar_path" '$@' >> spork
chmod 700 spork

echo "Fetching gumtree"
wget "https://github.com/GumTreeDiff/gumtree/releases/download/v2.1.2/gumtree.zip"
unzip gumtree.zip

export PATH="$PATH:$PWD:$PWD/gumtree-2.1.2/bin"

git checkout benchmark

cd scripts

python3 -m pip install -r requirements.txt
bench_returncode=$(python3 -m benchmark.cli merge-compare -r rxjava -g reactivex --compare expected_results.csv --merge-commands "spork merge" --output results.csv)

cat results.csv

exit bench_returncode
