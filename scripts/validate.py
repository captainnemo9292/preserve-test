#!/usr/bin/env python3
"""Validate generated tests: the repro method on the buggy version and on the developer fix.

scripts/generate.py already does this after generating a test; this re-runs it for existing
results. A test is valid when every input inside buggyIf fails on the buggy version, and every
input passes on the developer fix: the same (paired) inputs replayed, plus fresh fuzzing there.
The verdict is written to results/<bug>/result.json ("valid", "validation").

    python scripts/validate.py                 # every bug with a generated test
    python scripts/validate.py Lang-44 -j 2    # selected bugs
"""

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from preserve.config import DEFAULT_PAIRED_INPUTS, DEFAULT_REPRO_SECONDS, RESULTS_DIR  # noqa: E402
from preserve.repro import validate  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bugs", nargs="*", help="default: every bug with a generated test")
    ap.add_argument("--repro-seconds", type=int, default=DEFAULT_REPRO_SECONDS, help="fuzzing time per version")
    ap.add_argument("--paired-inputs", type=int, default=DEFAULT_PAIRED_INPUTS)
    ap.add_argument("-j", "--jobs", type=int, default=3, help="parallel bugs (the container has 4 CPUs)")
    a = ap.parse_args()
    bugs = a.bugs or sorted(d.name for d in RESULTS_DIR.iterdir() if (d / "result.json").exists()
                            and json.loads((d / "result.json").read_text()).get("test_file"))
    with ThreadPoolExecutor(a.jobs) as pool:
        futures = {pool.submit(validate, b, a.repro_seconds, a.paired_inputs): b for b in bugs}
        for f in as_completed(futures):
            v = f.result()
            b, f_ = v["on_buggy"], v["on_fix"]
            print(f"{futures[f]:9s} {'VALID  ' if v['valid'] else 'INVALID'} "
                  f"buggy: {b.get('outcomes', b.get('why'))} | fix: {f_['result']} "
                  f"{ {k: f_[k] for k in ('paired', 'fresh') if k in f_} if not v['valid'] else ''}"[:400], flush=True)


if __name__ == "__main__":
    main()
