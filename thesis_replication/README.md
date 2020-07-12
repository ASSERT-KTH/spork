# Reproduction of thesis experiments
This directory contains scripts and instructions for reproducing the
experimental evaluation of the master thesis for which Spork was created.

## Directory overview
The following results files are available in this directory:

* [candidate_projects.txt](candidate_projects.txt)
    - A list of candidate projects, as described in section 6.2 of the thesis.
* [buildable_candidates.txt](buildable_candidates.txt)
    - A list of candidate projects for which the last commit was buildable at
      the time of the thesis experiments. See section 6.2 of the thesis for details.
* [projects.csv](projects.csv)
    - A table of projects used in the evaluation.
* [stats_collection_file_merge_results](stats_collection_file_merge_results.csv)
    - This file contains results from individual file merges with JDime's stats
      collection turned on. Note that JDime's stats collection results in a hit
      to runtime performance. The reason it's turned on is that JDime does not
      report conflicts without it.
    - See section 6.3 in the thesis for a detailed description of the experiment.
* [no_stats_collection_file_merge_results](no_stats_collection_file_merge_results.csv)
    - This contains data from an execution of the same experiment as the one
      above, but with stats collection turned of in JDime. The runtimes for
      JDime are subsequently slightly better.
* [bytecode_experiment_results](bytecode_experiment_results.csv)
    - Results from the bytecode comparison experiment.
    - See section 6.4 in the thesis for a detailed description of this
      experiment.

For more detailed results, including the actual file merges computed by each
tool, you must download the replication package as described below.

## Setting up for the experiments
The [install.sh](install.sh) script is designed to setup the experiments on
Ubuntu 18.04. It will both fetch all required resources (including the
replication package), install required software, as well as configure Git and
other related software correctly. It may work with other Debian-based
distributions, but we have only tested it on Ubuntu 18.04. [Download Ubuntu
18.04 from here](https://releases.ubuntu.com/18.04.4/).

> **Important:** The install script will both install and remove packages, and
> so we do not recommend running it on a machine that is used for other things.
> We recommend using a dedicated install for this experiment, or a virtual
> machine such as VirtualBox.

> **Note:** If you just want to look at the detailed results, then you don't
> need to run the install script. Instead, refer to [Getting the replication
> package](#getting-the-replication-package).

The final output of the install script should tell you to source a file before
running the experiments. This file will set some environment variables and
activate a Python virtual environment in which the benchmark package is
installed. So source the file.

You should also have a directory called `replication_package` in your current
working directory. In order to replicate the experiments, go to
`replication_package/replication/replication_experiment` and run the
`run_experiments.py` file.

```bash
cd replication_package/replication/replication_experiment
python run_experiments.py
```

Again, don't forget to source the environment file first, or the experiments
will surely fail to run. We give a brief overview of the replication package in
[Replication package overview](#replication-package-overview), which may prove
helpful if you run into issues.

## Getting the replication package
You can download the replication package manually [from
here](https://github.com/KTH/spork/releases/download/v0.5.0/replication_package.tar.gz),
or simply run the following shell command:

```bash
curl https://github.com/KTH/spork/releases/download/v0.5.0/replication_package.tar.gz \
    -o replication_package.tar.gz
```

Then, unpack the replication package like so.

```bash
tar -xzf replication_package.tar.gz
```

This will leave you with a directory called `replication_package`, containing
all resources.

> **Important:** If you wish to actually run the experiments, use the install
> script as described in [Setting
> up for the experiments](#setting-up-for-the-experiments) instead.

## Replication package overview
There are two important directories in the replication package: `results` and
`replication`. In the `results` directory, you will find the results from the
experimental evaluation, such as merged source files and replayed class files.
In the `replication` directory, you will find the means of reproducing the
experiments, as well as running a new experiment on different projects.

### Replication directory overview
In the `replication` directory, you will find two subdirectories:
`replication_experiment` and `clean_experiment`, both of which contains a script
called `run_experiments.py` to execute the experiments. The
`replication_experiment` directory is intended to precisely replicate the
experiments that we ran, on the same projects and merge scenarios. The
`clean_experiment` directory lets you kick off a new experiment, with projects
being randomly chosen from the
[buildable_candidates.txt](buildable_candidates.txt) list.

Both experiments are executed by simply running:

```
python run_experiments.py
```

### Software
In `replication/software`, you will find additional software required for
running the experiments (except for `JDime`, which we clone on-demand). The
software used is as follows.

* `sootdiff-1.0-spork-experiments.jar` - Used to compare class files for
  equality
    - This is a [custom version of SootDiff](https://github.com/slarse/sootdiff)
* `duplicate-checkcast-remover-1.0.1.jar` - Used to remove duplicate checkcast
  instructions that may cause trouble for `SootDiff`
    - [See the repository for details](https://github.com/slarse/duplicate-checkcast-remover)
* `pkgextractor-1.0.1.jar` - Used to extract package statements from source and
  class files
    - [See the repository for details](https://github.com/slarse/pkgextractor)
* `spork-0.5.0.jar` - The version of `Spork` used for the experiments.

All of these jar files have associated shell scripts in
`replication/software/executables`, that just wrap the jar-files to make them
easier to execute during the benchmarks. These wrappers require that the
`EXPERIMENT_SOFTWARE_ROOT` variable is the absolute path to
`replication/software` (the `install.sh` script takes care of this) to work
correctly.

The actual benchmark script is located in `replication/software/benchmark`, and
is a Python package that can be installed with `pip`. If you use the
`install.sh` script, the benchmark package will already be installed, and you
can access its CLI by running:

```bash
python3 -m benchmark.cli -h
```

> **Important:** The benchmark package require that the wrappers in
> `replication/software/executables` are on the `PATH`, and that
> `EXPERIMENT_SOFTWARE_ROOT` is correctly set!
