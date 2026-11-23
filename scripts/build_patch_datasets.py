#!/usr/bin/env python3
"""Once: build the patch dataset under data/patches/ (format: preserve/patchsets.py).

  apr          Poracle's tool-generated patches: every config in Poracle's configs/ whose tool
               is not "developer" (458 patches of 77 bugs, labelled Correct / Incorrect). The
               config and the patch file are copied unchanged. These 77 bugs are the benchmark
               of the experiment (data/bugs77.txt).

    python scripts/build_patch_datasets.py
"""

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from preserve.config import PATCH_DATA, PORACLE_HOST, ROOT  # noqa: E402


def bug_key(bug: str) -> tuple[str, int]:
    return bug.split("-")[0], int(bug.split("-")[1])


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fresh(directory: Path) -> Path:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    return directory


def write_index(out: Path, records: list[dict]) -> None:
    records.sort(key=lambda r: (*bug_key(r["bug"]), r["id"]))
    (out / "index.json").write_text(json.dumps(records, indent=1) + "\n")


# --- apr --------------------------------------------------------------------------------------

def build_apr() -> list[str]:
    out = PATCH_DATA / "apr"
    configs, patches = fresh(out / "configs"), fresh(out / "patches")
    records = []
    for f in sorted((PORACLE_HOST / "configs").glob("*.json")):
        cfg = json.loads(f.read_text())
        if cfg["tool"] == "developer":
            continue
        pid = cfg["ID"]
        shutil.copyfile(f, configs / f"{pid}.json")
        shutil.copyfile(PORACLE_HOST / "patches" / pid, patches / pid)
        records.append({
            "id": pid, "dataset": "apr", "bug": f"{cfg['project']}-{cfg['bug_id']}", "tool": cfg["tool"],
            "label": cfg["correctness"], "target": cfg["target"],
            **{k: cfg[k] for k in ("threadName", "delta_allowed") if k in cfg},
            "patch_sha256": sha256((patches / pid).read_bytes())})
    write_index(out, records)
    bugs = sorted({r["bug"] for r in records}, key=bug_key)
    (ROOT / "data" / "bugs77.txt").write_text("\n".join(bugs) + "\n")
    labels = {l: sum(r["label"] == l for r in records) for l in ("Correct", "Incorrect")}
    print(f"apr: {len(records)} patches of {len(bugs)} bugs {labels} -> {out}")
    return bugs


def main():
    argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter).parse_args()
    build_apr()


if __name__ == "__main__":
    main()
