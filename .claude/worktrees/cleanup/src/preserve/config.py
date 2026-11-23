"""Paths and fixed settings."""

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
D4J_TABLES = ROOT / "data" / "defects4j"     # latest Defects4J active-bugs.csv per project
REPORTS_DIR = ROOT / "data" / "reports"      # fetched bug reports, one JSON per bug
PATCH_DATA = ROOT / "data" / "patches"       # patch datasets, one directory each (scripts/build_patch_datasets.py)
# outputs of generate.py / validate.py outside an experiment: per-bug results plus deltas/ (a Poracle deltas root)
RESULTS_DIR = Path(os.environ.get("PRESERVE_RESULTS_DIR", ROOT / "results")).resolve()
# host snapshots of the buggy checkouts (the agent reads these); PRESERVE_WORKSPACE_DIR overrides workspace/
WORKSPACE_DIR = Path(os.environ.get("PRESERVE_WORKSPACE_DIR", ROOT / "workspace")).resolve()

# Poracle's artifact on the host: benchmark bugs, build-file deltas, patch configs ($PRESERVE_PORACLE_HOST overrides)
PORACLE_HOST = Path(os.environ.get("PRESERVE_PORACLE_HOST", "/home/byoungwoo/patch_validation/poracle"))
CONTAINER = "poracle-reproduce"              # our tests run here, with the fuzzer changes of scripts/install_fuzzer.py
# Poracle's own tests run on Poracle's original fuzzer: a container made from the original image,
# which has none of the fuzzer changes installed in CONTAINER (scripts/install_fuzzer.py). Start it
# pinned to free cores (pick them first: the server is shared):
#   docker run -d --name poracle-original --cpuset-cpus <cores> poracle100/poracle-reproduce:latest /root/start.sh
PORACLE_CONTAINER = os.environ.get("PRESERVE_PORACLE_CONTAINER", "poracle-original")
# checkouts inside the container; separate runs that must not share them set PRESERVE_CONTAINER_WORK
CONTAINER_WORK = os.environ.get("PRESERVE_CONTAINER_WORK", "/tmp/preserve-testing")

PROJECTS = ["Math", "Lang", "Chart", "Time"]

REPRO_METHOD = "testBugReproduction"         # the agent's test method; the preservation method gets the trigger test's name

DEFAULT_MODEL = "sonnet"                     # a Claude CLI model name
DEFAULT_MAX_LLM_CALLS = 50                   # AutoCodeSherpa's budget
DEFAULT_FUZZ_SECONDS = 15                    # fuzzing per execution while the agent refines the test
DEFAULT_REPRO_SECONDS = 60                   # fuzzing per version when checking it with the repro method
DEFAULT_PAIRED_INPUTS = 20000                # inputs inside buggyIf kept from the buggy run for replay
