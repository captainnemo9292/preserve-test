"""Patch datasets: data/patches/<dataset>/, each in Poracle's format.

    index.json          one record per patch (see `Patch`)
    configs/<ID>.json   the Poracle config: ID, project, bug_id, tool, correctness, target
    patches/<ID>        a unified diff, applied to the buggy checkout with `patch -p1`

    apr          Poracle's tool-generated patches, labelled Correct / Incorrect

scripts/build_patch_datasets.py writes them and scripts/verify_patches.py checks that every
patch applies, compiles and passes the bug's trigger tests.
"""

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from preserve.config import CONTAINER, PATCH_DATA

DATASETS = ["apr"]


@dataclass(frozen=True)
class Patch:
    id: str
    dataset: str
    bug: str                    # e.g. Math-81
    tool: str
    label: str                  # Correct / Incorrect
    target: list[str]           # Poracle's targets: "<file>:<line>" in the patched version
    info: dict = field(default_factory=dict, compare=False, hash=False)     # the whole index record


def dataset_dir(dataset: str) -> Path:
    return PATCH_DATA / dataset


def load(dataset: str) -> list[Patch]:
    index = dataset_dir(dataset) / "index.json"
    if not index.exists():
        raise FileNotFoundError(f"{index} is missing: run scripts/build_patch_datasets.py")
    return [Patch(r["id"], dataset, r["bug"], r["tool"], r["label"], r["target"], r)
            for r in json.loads(index.read_text())]


def experiment_bugs() -> list[str]:
    """The benchmark of the experiment: the bugs that have a patch in Poracle's APR dataset."""
    bugs = {p.bug for p in load("apr")}
    return sorted(bugs, key=lambda b: (b.split("-")[0], int(b.split("-")[1])))


def copy_to_container(dataset: str, dest: str, container: str = CONTAINER) -> str:
    """Copy a dataset's configs/ and patches/ to `dest` in the container (a directory Poracle
    can be run from: it reads patches/<ID> next to its work directory). Returns `dest`."""
    subprocess.run(["docker", "exec", container, "bash", "-c", f"rm -rf {dest} && mkdir -p {dest}"], check=True)
    for sub in ("configs", "patches"):
        subprocess.run(["docker", "cp", str(dataset_dir(dataset) / sub), f"{container}:{dest}/{sub}"],
                       check=True, capture_output=True)
    return dest
