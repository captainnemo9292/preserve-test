#!/usr/bin/env python3
"""Step 1: generate a test for Defects4J bugs (the repro method and the derived preservation
method), and validate it on the buggy version and the developer fix.

    python scripts/generate.py Math-35                 # one bug (add -v to watch the full log)
    python scripts/generate.py -f data/bugs15.txt -j 2 # a list of bugs, 2 at a time
    python scripts/generate.py --all --resume          # every usable bug not done yet

Outputs go to results/<bug>/ (PRESERVE_RESULTS_DIR overrides results/).
"""

import argparse
import json
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from preserve.bugs import usable_bugs  # noqa: E402
from preserve.config import DEFAULT_FUZZ_SECONDS, DEFAULT_MAX_LLM_CALLS, DEFAULT_MODEL, RESULTS_DIR  # noqa: E402
from preserve.pipeline import generate  # noqa: E402


def run_one(bug: str, a: argparse.Namespace) -> dict:
    try:
        return generate(bug, model=a.model, fuzz_seconds=a.fuzz_seconds, max_llm_calls=a.max_llm_calls,
                        verbose=a.verbose)
    except Exception as e:      # keep the batch going; the error is the result
        return {"bug": bug, "success": False, "failure_reason": f"{type(e).__name__}: {e}"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bugs", nargs="*", help="bug names, e.g. Math-35")
    ap.add_argument("-f", "--file", help="file with one bug name per line")
    ap.add_argument("--all", action="store_true", help="every benchmark bug with a fetched report")
    ap.add_argument("--resume", action="store_true", help="skip bugs that already have a result.json")
    ap.add_argument("-j", "--jobs", type=int, default=2, help="parallel bugs (the container has 4 CPUs)")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="Claude CLI model name")
    ap.add_argument("--fuzz-seconds", type=int, default=DEFAULT_FUZZ_SECONDS,
                    help="fuzzing time per execution while the agent refines the test")
    ap.add_argument("--max-llm-calls", type=int, default=DEFAULT_MAX_LLM_CALLS)
    ap.add_argument("-v", "--verbose", action="store_true", help="print the full log (single bug)")
    a = ap.parse_args()

    bugs = list(a.bugs)
    if a.file:
        bugs += [l.strip() for l in Path(a.file).read_text().splitlines() if l.strip() and not l.startswith("#")]
    if a.all:
        bugs += [b.name for b in usable_bugs()]
    if a.resume:
        bugs = [b for b in bugs if not (RESULTS_DIR / b / "result.json").exists()]
    if not bugs:
        ap.error("no bugs to run")

    print(f"{len(bugs)} bugs, {a.jobs} at a time; model {a.model}, {a.fuzz_seconds}s fuzzing per execution, "
          f"{a.max_llm_calls} LLM calls; results in {RESULTS_DIR}")
    results = []
    with ProcessPoolExecutor(a.jobs) as pool:
        for fut in as_completed([pool.submit(run_one, b, a) for b in bugs]):
            r = fut.result()
            results.append(r)
            status = ("FAILED  " + r.get("failure_reason", "")[:150] if not r["success"]
                      else "VALID" if r.get("valid") else
                      "INVALID  " + (r.get("validation", {}).get("why_invalid") or "not validated"))
            print(f"[{len(results)}/{len(bugs)}] {r['bug']}: {status}", flush=True)
    ok = sum(bool(r.get("valid")) for r in results)
    print(f"\n{ok}/{len(results)} valid tests")
    if len(results) == 1 and not a.verbose:
        print(json.dumps({k: v for k, v in results[0].items() if k != "tokens"}, indent=2))
    sys.exit(0 if ok == len(results) else 1)


if __name__ == "__main__":
    main()
