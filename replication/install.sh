#! /bin/bash
# Script for setting up the experiments on Ubuntu 18.04.
#
# IMPORTANT: This is specifically designed to set up the experiments on a clean
# install of Ubuntu 18.04. We discourage running this script in an environment
# that is used for other activites, unless you read through all of it and
# understand all details, as it both installs and uninstall packages and changes
# global Git configuration.
#
# Making this work for other distributions will likely require
# some amount of work.

REPLICATION_PKG_URL="https://github.com/KTH/spork/releases/v0.5.0/replication_package.tar.gz"
REPLICATION_PKG_DIR="$PWD/replication_package"
ENV_FILE="$REPLICATION_PKG_DIR/env.sh"

function download_replication_package() {
    curl "$REPLICATION_PKG_URL" -o replication_package.tar.gz
    tar -xvzf replication_package.tar.gz
}

function install_prerequisites() {
    # Install prerequisites that are available in the official Ubuntu repositories.
    sudo add-apt-repository universe \
        && sudo apt update || exit 1

    # Install dependencies
    sudo apt install -y gradle maven git libgit2-dev curl || exit 1
}

function install_jdk8() {
    # Install OpenJDK8 and OpenJFX8 for Ubuntu 18.04, and set it as the default JDK
    sudo apt purge openjfx \
        && sudo apt install -y openjdk-8-jdk openjfx=8u161-b12-1ubuntu2 libopenjfx-jni=8u161-b12-1ubuntu2 libopenjfx-java=8u161-b12-1ubuntu2 \
        && sudo apt-mark hold openjfx libopenjfx-jni libopenjfx-java || exit 1

    java_path="$(update-java-alternatives -l | grep java-1.8.0 | awk '{ print $3 }')"
    sudo update-java-alternatives --set "$java_path"
}

function install_python38() {
    sudo add-apt-repository -y ppa:deadsnakes/ppa || exit 1
    sudo apt update || exit 1
    sudo apt install -y python3.8 python3-dev python3-pip || exit 1
}

function build_jdime() {
    git clone https://github.com/se-sic/jdime.git "$REPLICATION_PKG_DIR/replication/software/jdime" \
        && cd "$REPLICATION_PKG_DIR/replication/software/jdime" \
        && git checkout 100aeecedcd36cd7e9165fd0495c7781344593e0 \
        && ./gradlew installDist \
        && cd - || exit 1
}

function install_benchmark_scripts() {
    python3 -m pip install pipenv \
        && python3 -m pipenv install pip \
        && python3 -m pipenv install -r "$REPLICATION_PKG_DIR/replication/software/benchmark-scripts/requirements.txt" \
        && python3 -m pipenv install "$REPLICATION_PKG_DIR/replication/software/benchmark-scripts" || exit 1
}

function setup_executables() {
    echo "# Source this file before running experiments" > "$ENV_FILE"
    echo 'export PATH="$PATH'":$REPLICATION_PKG_DIR/replication/software/executables\"" >> "$ENV_FILE"
    echo "export EXPERIMENT_SOFTWARE_ROOT=\"$REPLICATION_PKG_DIR/replication/software\"" >> "$ENV_FILE"
    echo 'python3 -m pipenv shell' >> "$ENV_FILE"
}

function setup_git() {
    cp "$REPLICATION_PKG_DIR/replication/gitconfig" "$HOME/.gitconfig"
    git config --global user.email "example@example.com"
    git config --global user.name "Experimenter Experimentson"
}

download_replication_package
install_prerequisites
install_jdk8
install_python38
install_benchmark_scripts
build_jdime
setup_executables
setup_git

echo "
########################################################################
  Experiments have been setup. Before running the experiments, execute:   
                                             
        source $ENV_FILE                     
                                             
########################################################################
"
