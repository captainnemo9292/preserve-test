"""Checking a version of the code with the repro method.

The repro method only looks at inputs inside buggyIf, and its assertions say what the fixed code
must do there. So on the buggy version every such input must fail, and on a version that fixes
the bug every one must pass. A version is checked on two sets of inputs:

  paired  the buggy version is fuzzed once per bug, and the inputs inside buggyIf are kept
          (`fuzz_buggy`). They are replayed on the other version, so both versions are checked
          on identical arguments.
  fresh   the other version is fuzzed itself, which can reach code that only exists there.

`validate` uses this to decide whether a generated test is valid: all paired inputs fail on the
buggy version, and all inputs, paired and fresh, pass on the developer fix.
"""

import collections
import json

from preserve.bugs import Bug
from preserve.config import DEFAULT_PAIRED_INPUTS, DEFAULT_REPRO_SECONDS, RESULTS_DIR
from preserve.container import Checkout
from preserve.jqf import fuzz, install_test, replay, replay_examples


def _test(bug: str) -> tuple[str, str, str]:
    """(test class, repro method, source of the final test file) of a generated test."""
    res = json.loads((RESULTS_DIR / bug / "result.json").read_text())
    return res["jqf_class"], res["repro_method"], (RESULTS_DIR / bug / res["test_file"]).read_text()


def _test_path(checkout: Checkout, test_class: str) -> str:
    return f"{checkout.dir}/{checkout.test_dir}/{test_class.replace('.', '/')}.java"


def paired_dir(bug: str) -> str:
    return f"{Checkout(Bug.parse(bug)).workdir}/paired-inputs"


def fuzz_buggy(bug: str, seconds: int = DEFAULT_REPRO_SECONDS, paired_inputs: int = DEFAULT_PAIRED_INPUTS,
               buggy: Checkout | None = None) -> dict:
    """Fuzz the repro method on the buggy version and keep its inputs inside buggyIf as the bug's
    paired inputs (in the container, with their outcomes in results/<bug>/paired.json)."""
    cls, method, source = _test(bug)
    buggy = buggy or Checkout(Bug.parse(bug)).prepare()
    errors = install_test(buggy.env, source, _test_path(buggy, cls), buggy.compile_tests)
    if errors is not None:
        return {"result": "unknown", "why": "the test does not compile on the buggy version", "errors": errors[:1500]}
    run = fuzz(buggy.env, cls, method, buggy.classpath, f"{buggy.workdir}/fuzz-buggy", seconds,
               classify=None, keep=(paired_dir(bug), paired_inputs))
    counts = dict(collections.Counter(run.outcomes.values()))
    passed = counts.get("SUCCESS", 0)
    row = {"result": "unknown" if not run.outcomes else "pass" if passed == len(run.outcomes)
                     else "fail" if passed == 0 else "mixed",
           "trials": run.trials, "inside_buggyIf": run.valid, "paired_inputs": len(run.outcomes),
           "outcomes": counts}
    if passed and run.passing:
        row["passing_examples"] = run.passing
    (RESULTS_DIR / bug / "paired.json").write_text(json.dumps({"dir": paired_dir(bug), "outcomes": run.outcomes}))
    return row


def paired_outcomes(bug: str) -> dict[str, str]:
    """The buggy version's outcome for each paired input, if the inputs are still in the container."""
    f = RESULTS_DIR / bug / "paired.json"
    if not f.exists():
        return {}
    outcomes = json.loads(f.read_text())["outcomes"]
    env = Checkout(Bug.parse(bug)).env
    n = env.exec_cmd(f"ls {paired_dir(bug)} 2>/dev/null | wc -l").stdout.strip().splitlines()[-1:]
    return outcomes if n and n[0] == str(len(outcomes)) else {}


def check_version(checkout: Checkout, bug: str, seconds: int = DEFAULT_REPRO_SECONDS) -> dict:
    """The repro method on a version that should fix the bug (the developer fix or a patch):
    replay of the bug's paired inputs, plus fresh fuzzing. result: pass / fail / unknown."""
    cls, method, source = _test(bug)
    errors = install_test(checkout.env, source, _test_path(checkout, cls), checkout.compile_tests)
    if errors is not None:
        return {"result": "unknown", "why": "the test does not compile on this version", "errors": errors[:1500]}

    on_buggy = paired_outcomes(bug)
    on_this = replay(checkout.env, cls, method, checkout.classpath, paired_dir(bug)) if on_buggy else {}
    failing = sorted(f for f in on_buggy if on_this.get(f, "INVALID") not in ("SUCCESS", "INVALID"))
    paired: dict = {"inputs": len(on_buggy),
                    "failed_on_buggy": sum(o != "SUCCESS" for o in on_buggy.values()),
                    "pass": sum(on_this.get(f) == "SUCCESS" for f in on_buggy),
                    "fail": len(failing),
                    "not_run": sum(f not in on_this for f in on_buggy)}
    if failing:
        shown = replay_examples(checkout.env, cls, method, checkout.classpath, paired_dir(bug), failing[:3])
        paired["failing_examples"] = [f"{shown[f][0]}\n{shown[f][1][:600]}" for f in failing[:3] if f in shown]

    run = fuzz(checkout.env, cls, method, checkout.classpath, f"{checkout.dir}-fuzz", seconds)
    fresh: dict = {"trials": run.trials, "inside_buggyIf": run.valid,
                   "unique_failures": {k: run.counts[k] for k in run.failing}}
    if run.failing:
        fresh["failing_examples"] = run.show_failing()[:1500]

    if failing or run.failing:
        result = "fail"
    elif paired["pass"] == 0 and run.valid == 0:
        result = "unknown"          # no input inside buggyIf was checked
    else:
        result = "pass"
    return {"result": result, "paired": paired, "fresh": fresh}


def validate(bug: str, seconds: int = DEFAULT_REPRO_SECONDS, paired_inputs: int = DEFAULT_PAIRED_INPUTS,
             buggy: Checkout | None = None) -> dict:
    """Is the generated test valid? Every paired input must fail on the buggy version, and every
    input, paired and fresh, must pass on the developer fix. Updates results/<bug>/result.json."""
    on_buggy = fuzz_buggy(bug, seconds, paired_inputs, buggy)
    on_fix: dict = {"result": "not run"}
    if on_buggy["result"] == "fail":
        try:
            on_fix = check_version(Checkout(Bug.parse(bug), "f").prepare(), bug, seconds)
        except Exception as e:
            on_fix = {"result": "unknown", "why": f"{type(e).__name__}: {e}"[:800]}
    valid = (on_buggy["result"] == "fail" and on_fix["result"] == "pass"
             and on_fix["paired"]["pass"] == on_fix["paired"]["inputs"])
    if valid:
        why = ""
    elif on_buggy["result"] != "fail":
        why = {"mixed": "some inputs inside buggyIf pass on the buggy version",
               "pass": "no input inside buggyIf fails on the buggy version",
               "unknown": on_buggy.get("why", "no input inside buggyIf was generated")}[on_buggy["result"]]
    elif on_fix["result"] == "fail":
        why = "some inputs inside buggyIf fail on the developer fix"
    else:
        why = on_fix.get("why", "not every paired input was checked on the developer fix")
    validation = {"valid": valid, "why_invalid": why, "on_buggy": on_buggy, "on_fix": on_fix}
    f = RESULTS_DIR / bug / "result.json"
    f.write_text(json.dumps({**json.loads(f.read_text()), "valid": valid, "validation": validation},
                            indent=2, ensure_ascii=False))
    return validation
