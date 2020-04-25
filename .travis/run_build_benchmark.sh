#! /bin/bash

echo "Setting JAVA_HOME"
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
echo $JAVA_HOME

echo "Compiling spork"
mvn clean compile package -DskipTests
spork_jar_path="$PWD/$(ls target/spork*.jar)"

echo "Creating spork executable"
echo "#! /bin/bash" > spork
echo "java -jar $spork_jar_path" '$@' >> spork
chmod 700 spork

mv spork ~/

cat "$TRAVIS_BUILD_DIR/.travis/gitconfig" >> ~/.gitconfig
cat "$TRAVIS_BUILD_DIR/.travis/gitattributes" >> ~/.gitattributes

git checkout benchmark

cd scripts || exit

python3 -m pip install -r requirements.txt
python3 -m benchmark.cli run-git-merges \
  -r spoon \
  -g inria \
  --merge-commits buildable_spoon_merges.txt \
  --build \
  --output results.csv

cat results.csv

if [ "$(cat results.csv | grep False)" ]; then exit 1; fi

exit 0
