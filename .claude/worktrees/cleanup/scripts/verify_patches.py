#!/usr/bin/env python3
"""Confirm that the patches of a dataset (data/patches/<dataset>/) can be applied as intended.

Each patch goes through the steps Poracle and the experiment take, in the Poracle container:

  applies     buggy checkout + the bug's Poracle delta, then `patch -p1 < patches/<ID>`.
              `inexact` lists the hunks `patch` placed with an offset or fuzz.
  compiles    `defects4j compile`
  plausible   every trigger test Defects4J lists for the bug passes on the patched version,
              apart from tests that fail on the developer-fixed version too in this container
              (`fails_on_developer_fix`: checked for every bug with a failing patch).
              `plausible_for_poracle` is the plain result for the tests Poracle's own
              plausibility step runs (it rejects a patch that fails one without fuzzing it);
              some of Poracle's deltas narrow that list to the tests it has generalized.

The rows go to data/patches/<dataset>/verification.json; patches that already have a row are
skipped (--redo re-runs them).

    python scripts/verify_patches.py -j 3             # every dataset (apr)
    python scripts/verify_patches.py apr --ids Patch7 Patch26 --redo
"""

import argparse
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

os.environ.setdefault("PRESERVE_CONTAINER_WORK", "/tmp/preserve-testing-verify")
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from preserve import patchsets  # noqa: E402
from preserve.bugs import Bug  # noqa: E402
from preserve.config import CONTAINER_WORK  # noqa: E402
from preserve.container import Checkout, CheckoutError  # noqa: E402


def verify(patch: patchsets.Patch, patches_dir: str) -> dict:
    row: dict = {"id": patch.id, "bug": patch.bug, "tool": patch.tool, "label": patch.label,
                 "applies": False, "compiles": False, "plausible": False}
    started = time.time()
    checkout = Checkout(Bug.parse(patch.bug), patch=patch.id, patches_dir=patches_dir)
    try:
        checkout.prepare(compile=False)
        row["applies"] = True
        row["patched_files"] = re.findall(r"^patching file (.+)$", checkout.patch_output, re.MULTILINE)
        row["inexact"] = [l.strip() for l in checkout.patch_output.splitlines() if re.search(r"offset|fuzz", l)]
        checkout.compile()
        row["compiles"] = True
        poracle_tests = checkout.trigger_tests()        # the tests Poracle's plausibility step runs
        tests = checkout.all_trigger_tests()
        row["trigger_tests"] = checkout.run_trigger_tests(tests + [t for t in poracle_tests if t not in tests])
        row["plausible"] = bool(tests) and all(row["trigger_tests"][t] for t in tests)
        row["plausible_for_poracle"] = bool(poracle_tests) and all(row["trigger_tests"][t] for t in poracle_tests)
    except CheckoutError as e:
        row["error"] = str(e)[-1500:]
    finally:
        checkout.remove()
    row["seconds"] = round(time.time() - started, 1)
    return row


def tests_failing_on_fix(bug: str) -> list[str]:
    """The bug's trigger tests that fail even on the developer-fixed version in this container
    (prepared like a patched version: checkout, delta, compile). Such a test says nothing about
    a patch: it fails for a reason of the environment, e.g. the JDK's locale data."""
    fixed = Checkout(Bug.parse(bug), "f")
    try:
        fixed.prepare()
        results = fixed.run_trigger_tests(fixed.all_trigger_tests())
    finally:
        fixed.remove()
    return sorted(t for t, passed in results.items() if not passed)


def recheck_against_fix(rows: dict[str, dict], jobs: int) -> None:
    """For every bug with a patch that fails a trigger test: which of those tests fail on the
    developer fix too. They are recorded (`fails_on_developer_fix`) and no longer count
    against `plausible`; `plausible_for_poracle` stays what Poracle itself will see."""
    suspects = sorted({r["bug"] for r in rows.values() if not all(r.get("trigger_tests", {}).values())})
    with ThreadPoolExecutor(jobs) as pool:
        on_fix = dict(zip(suspects, pool.map(tests_failing_on_fix, suspects)))
    for r in rows.values():
        if r["bug"] in on_fix and r.get("trigger_tests"):
            r["fails_on_developer_fix"] = on_fix[r["bug"]]
            r["plausible"] = all(passed for t, passed in r["trigger_tests"].items() if t not in on_fix[r["bug"]])
    for bug in suspects:
        print(f"  {bug}: trigger tests that fail on the developer fix too: {on_fix[bug] or 'none'}", flush=True)


def ok(row: dict) -> bool:
    return row["applies"] and row["compiles"] and row["plausible"]


def unchecked(row: dict) -> bool:
    """The buggy version could not even be checked out, so the patch was never tried."""
    return row.get("error", "").startswith(("checkout failed", "delta.sh failed"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("datasets", nargs="*", help=f"default: {' '.join(patchsets.DATASETS)}")
    ap.add_argument("--ids", nargs="+", help="only these patch IDs")
    ap.add_argument("-j", "--jobs", type=int, default=3, help="parallel patches (the container has 4 CPUs)")
    ap.add_argument("--redo", action="store_true", help="re-run patches that already have a row")
    a = ap.parse_args()

    failed = 0
    for dataset in a.datasets or patchsets.DATASETS:
        out = patchsets.dataset_dir(dataset) / "verification.json"
        rows = {r["id"]: r for r in json.loads(out.read_text())} if out.exists() else {}
        patches = [p for p in patchsets.load(dataset) if not a.ids or p.id in a.ids]
        # a row whose checkout failed says nothing about the patch (the container was not
        # usable then): it is kept out of the file and done again
        rows = {i: r for i, r in rows.items() if not unchecked(r)}
        todo = [p for p in patches if a.redo or p.id not in rows]
        print(f"{dataset}: {len(patches)} patches, {len(todo)} to verify, {a.jobs} at a time", flush=True)
        root = patchsets.copy_to_container(dataset, f"{CONTAINER_WORK}/datasets/{dataset}")
        lock = threading.Lock()
        with ThreadPoolExecutor(a.jobs) as pool:
            for n, f in enumerate(as_completed([pool.submit(verify, p, f"{root}/patches") for p in todo]), 1):
                row = f.result()
                with lock:
                    if not unchecked(row):
                        rows[row["id"]] = row
                    out.write_text(json.dumps(sorted(rows.values(), key=lambda r: r["id"]), indent=1) + "\n")
                print(f"[{n}/{len(todo)}] {row['bug']:9s} {row['id']:36s} "
                      f"{'NOT CHECKED' if unchecked(row) else 'OK ' if ok(row) else 'BAD'} "
                      f"applies={row['applies']} compiles={row['compiles']} plausible={row['plausible']}"
                      f"{' inexact=' + str(len(row['inexact'])) if row.get('inexact') else ''} "
                      f"{row['seconds']}s {row.get('error', '')[:300]!r}", flush=True)

        recheck_against_fix(rows, a.jobs)
        out.write_text(json.dumps(sorted(rows.values(), key=lambda r: r["id"]), indent=1) + "\n")
        mine = [rows[p.id] for p in patches if p.id in rows]
        count = lambda key: sum(bool(r.get(key)) for r in mine)     # noqa: E731
        bad = [r["id"] for r in mine if not ok(r)]
        failed += len(bad) + len(patches) - len(mine)
        print(f"{dataset}: {len(mine)}/{len(patches)} verified: applies {count('applies')}, "
              f"compiles {count('compiles')}, plausible {count('plausible')} "
              f"(for Poracle {count('plausible_for_poracle')})"
              + f"; placed with an offset or fuzz: {sum(bool(r.get('inexact')) for r in mine)}"
              + (f"\n  NOT OK: {' '.join(bad)}" if bad else "")
              + (f"\n  NOT CHECKED (run again): {' '.join(p.id for p in patches if p.id not in rows)}"
                 if len(mine) < len(patches) else ""), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
