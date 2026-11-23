"""Generate the test for one Defects4J bug.

    agent.py   bug report -> bug-reproducing JQF test, refined on the buggy version
    derive.py  repro test -> preservation test, written as a Poracle delta
    repro.py   is it valid? the repro method on the buggy version and on the developer fix

The buggy checkout is prepared in the Poracle container exactly as Poracle prepares it, and a
host snapshot of it is what the model reads.
"""

import json
import shutil
import time
from pathlib import Path

from preserve.agent import ReproAgent, TestTarget
from preserve.bugs import Bug, report_text
from preserve.config import (DEFAULT_FUZZ_SECONDS, DEFAULT_MAX_LLM_CALLS, DEFAULT_MODEL, PORACLE_HOST,
                             REPRO_METHOD, RESULTS_DIR, WORKSPACE_DIR)
from preserve.container import Checkout, jqf_test_unit
from preserve.derive import derive
from preserve.llm import Trace, VerboseLog
from preserve.repro import validate


def generate(bug_name: str, out_dir: Path | None = None, model: str = DEFAULT_MODEL,
             fuzz_seconds: int = DEFAULT_FUZZ_SECONDS, max_llm_calls: int = DEFAULT_MAX_LLM_CALLS,
             verbose: bool = False) -> dict:
    """Run steps 1 and 2 for a bug; write and return result.json."""
    bug = Bug.parse(bug_name)
    out = Path(out_dir or RESULTS_DIR / bug.name)
    out.mkdir(parents=True, exist_ok=True)
    for old in [out / "result.json", out / "repro.java", out / "paired.json", *out.glob("JQF_*.java")]:
        old.unlink(missing_ok=True)
    shutil.rmtree(out / "attempts", ignore_errors=True)
    log = VerboseLog(out / "verbose.log", echo=verbose)
    trace = Trace(out / "trace.jsonl")      # everything, machine-readable: LLM events, executions, phases
    started = time.time()
    started_at = time.strftime("%Y-%m-%dT%H:%M:%S")
    timing: dict[str, float] = {}           # seconds per phase

    def phase(name: str, since: float, **fields) -> None:
        timing[name] = round(time.time() - since, 1)
        trace.write("phase", name=name, seconds=timing[name], **fields)

    # the buggy version, and the test Poracle will look for (its test1: the first trigger test)
    issue = report_text(bug)
    checkout = Checkout(bug, "b").prepare()
    failing_test = checkout.trigger_tests()[0]
    test_class, preserve_method = jqf_test_unit(failing_test)
    repro_method = REPRO_METHOD     # not derived from the trigger test's name: that would hint at it
    path_rel = f"{checkout.test_dir}/{test_class.replace('.', '/')}.java"
    snapshot = checkout.snapshot_to(WORKSPACE_DIR / bug.name / "source")
    log.section(f"BUG {bug.name}: failing test {failing_test} -> {test_class}.{repro_method}; "
                f"checkout {checkout.dir}; agent root {snapshot}", issue)
    log.section(f"HIDDEN FROM THE AGENT: {len(checkout.hidden_tests)} relevant test classes",
                "\n".join(checkout.hidden_tests))
    phase("checkout", started, bug=bug.name, model=model, failing_test=failing_test, test_class=test_class,
          snapshot=str(snapshot), hidden_tests=checkout.hidden_tests, issue=issue,
          fuzz_seconds=fuzz_seconds, max_llm_calls=max_llm_calls)

    # the agent writes the repro test
    target = TestTarget(env=checkout.env, compile_fn=checkout.compile_tests,
                        path=f"{checkout.dir}/{path_rel}", path_rel=path_rel, test_class=test_class,
                        test_method=repro_method, classpath=checkout.classpath, workdir=checkout.workdir,
                        fuzz_seconds=fuzz_seconds)
    agent = ReproAgent(str(snapshot), target, model, log, max_llm_calls, trace, out / "attempts")
    agent_started = time.time()
    final = agent.run(issue)
    log.section(f"AGENT FINISHED success={final.get('success')} llm_calls={agent.llm_calls}",
                final.get("failure_reason") or "")
    phase("agent", agent_started, success=bool(final.get("success")), failure_reason=final.get("failure_reason") or "",
          llm_calls=agent.llm_calls, cost_usd=agent.llm.cost_usd, tokens=agent.llm.tokens)

    run = final.get("run")
    result: dict = {
        "bug": bug.name, "success": bool(final.get("success")),
        "failure_reason": final.get("failure_reason") or "",
        "model": model, "llm_call_count": agent.llm_calls, "cost_usd": round(agent.llm.cost_usd, 4),
        "tokens": agent.llm.tokens, "seconds": round(time.time() - started, 1),
        "started_at": started_at, "timing": timing,
        "llm_seconds": round(sum(c["seconds"] for c in agent.llm.calls), 1),
        "llm_calls": agent.llm.calls,       # per CLI call: step, seconds, turns, tokens, cost
        "test_versions": agent.attempts,    # versions of the test the model wrote (attempts/NNN.java)
        "failing_test": failing_test, "jqf_class": test_class, "hidden_tests": checkout.hidden_tests,
        "repro_method": repro_method, "preserve_method": preserve_method,
        "issue_gist": final.get("issue_gist", ""),
        "failing_inputs_count": run.n_failing if run else 0,
    }
    if final.get("test"):
        (out / "repro.java").write_text(final["test"])

    # the preservation test is derived from it
    if final.get("success"):
        derive_started = time.time()
        result.update(_derive_and_package(checkout, final["test"], repro_method, preserve_method,
                                          path_rel, out, log))
        phase("derive", derive_started, **{k: result.get(k) for k in ("derived", "derived_compiles", "derive_error")})
    (out / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))

    # is the test valid? every input inside buggyIf fails on buggy and passes on the developer fix
    if result.get("derived_compiles"):
        validation_started = time.time()
        validation = validate(bug.name, buggy=checkout)
        log.section(f"VALIDATION valid={validation['valid']} {validation['why_invalid']}", validation)
        phase("validation", validation_started, **validation)
        result = json.loads((out / "result.json").read_text())
    result.update(timing=timing, total_seconds=round(time.time() - started, 1))
    (out / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
    return result


def _derive_and_package(checkout: Checkout, repro_src: str, repro_method: str, preserve_method: str,
                        path_rel: str, out: Path, log: VerboseLog) -> dict:
    """Derive the preservation test, check that it compiles, and write a Poracle delta."""
    try:
        final_src = derive(repro_src, repro_method, preserve_method)
    except ValueError as e:
        return {"derived": False, "derive_error": str(e)}
    name = Path(path_rel).name
    (out / name).write_text(final_src)
    checkout.env.write(f"{checkout.dir}/{path_rel}", final_src.encode())
    ok, errors = checkout.compile_tests()
    info: dict = {"derived": True, "derived_compiles": ok, "test_file": name}
    if not ok:
        info["derived_compile_errors"] = errors[:3000]
    else:
        # Poracle delta: the bug's build files from Poracle's delta + our test (never Poracle's)
        bug = checkout.bug
        delta_name = Path(bug.project) / f"{bug.project}_bug{bug.bug_id}"
        src, dst = PORACLE_HOST / "deltas" / delta_name, RESULTS_DIR / "deltas" / delta_name
        if dst.exists():
            shutil.rmtree(dst)
        for f in src.rglob("*"):
            if f.is_file() and not f.name.startswith("JQF_"):
                (dst / f.relative_to(src)).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dst / f.relative_to(src))
        (dst / path_rel).parent.mkdir(parents=True, exist_ok=True)
        (dst / path_rel).write_text(final_src)
        info["poracle_delta"] = str(dst)
    log.section("DERIVED PRESERVATION TEST", info)
    log.section(f"FINAL TEST FILE {name} (repro method + derived preservation method)", final_src)
    return info
