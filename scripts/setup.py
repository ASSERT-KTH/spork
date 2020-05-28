from setuptools import find_packages, setup

setup(
    name="merge-benchmark-utility",
    version="0.0.1",
    description="A benchmark utility for merge tools",
    packages=find_packages(where=".", include=["benchmark"])
)
