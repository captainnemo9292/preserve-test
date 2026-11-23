#!/usr/bin/env python3
"""The experiment: LLM-generated tests against Poracle's hand-written tests, on Poracle's APR patch dataset.

  generate   For each benchmark bug (the 77 Defects4J bugs with a patch in Poracle's APR dataset,
             data/bugs77.txt): bug report -> repro test, written by the LLM agent -> derived
             preservation test, then validated on the buggy version and the developer fix
             (preserve/pipeline.py). A bug without a bug report gets no test.
  evaluate   For each patch of each dataset (data/patches/apr), up to
             two suites, each with its own result file:
               ours     repro     our repro test on the patched version: pass / fail
                        preserve  our preservation test, differential fuzzing buggy vs patched: A / R
                        verdict   R if repro fails or preserve rejects
               poracle  preserve  Poracle's own test, same duration and seed, on Poracle's ORIGINAL
                                  fuzzer (--poracle-container, a container of the original image;
                                  ours runs with our fuzzer changes, in config.CONTAINER): A / R
             Only valid generated tests are used (--no-gate uses every test that was derived).
             A Poracle result made in another container than --poracle-container counts as not
             done: it is moved to <patch>/superseded/<container>/ and run again.
  report     Tables and statistics from whatever has been generated and evaluated so far:
               apr          labelled patches. A test is right on a patch when it fails (R) on an
                            Incorrect patch and passes (A) on a Correct one. The baseline
                            comparison is preservation test against preservation test (ours vs
                            Poracle's), on the patches both gave a verdict for; our repro +
                            preserve result is reported separately.
  all        generate, evaluate, report.

Every stage resumes: a bug or (patch, suite, repetition) that already has a result is skipped
unless --redo is given, so an interrupted run is continued by running the same command again.

    python scripts/run_experiment.py all                              # the whole experiment
    python scripts/run_experiment.py generate -j 2 --model sonnet
    python scripts/run_experiment.py evaluate --duration 3m -j 3
    python scripts/run_experiment.py evaluate --suites poracle --reps 3
    python scripts/run_experiment.py report
    python scripts/run_experiment.py all --out experiments/smoke --bugs Math-35 --patches Patch188

Outputs, under --out (default experiments/main):

    experiment.json, experiment.log     every invocation's settings; everything printed
    generation/<bug>/                   result.json (success, valid, validation, LLM calls, tokens,
                                        cost, time per phase), repro.java, JQF_<Class>.java (repro +
                                        preservation method), attempts/NNN.java (every version the
                                        agent wrote), trace.jsonl (every LLM event: the model's text,
                                        tool calls and results, usage and cost per call; every
                                        execution; every graph node), verbose.log (the same, readable),
                                        paired.json + paired-inputs.tar.gz (the buggy run's inputs)
    generation/deltas/                  our tests as a Poracle deltas directory
    workspace/<bug>/source              the snapshot of the buggy checkout the agent read
    evaluation/<dataset>/rep<k>/<patch>/  ours.json, poracle.json, and Poracle's log and
                                        statistics.json of each run
    evaluation/apr/poracle_stored.json  Poracle's own published verdicts for its patches
    report/                             summary.md, stats.json, generation.csv, <dataset>_patches.csv
"""

import argparse
import collections
import csv
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
import traceback
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SUITES = ["ours", "poracle"]


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["generate", "evaluate", "report", "all"])
    ap.add_argument("--out", default="experiments/main", help="experiment directory (default: %(default)s)")
    ap.add_argument("--bugs", nargs="+", help="only these bugs, e.g. Math-35 (default: the 77 benchmark bugs)")
    ap.add_argument("-f", "--bug-file", help="only the bugs in this file, one name per line")
    ap.add_argument("-j", "--jobs", type=int, default=3,
                    help="parallel patches in evaluate (the container has 4 CPUs; default: %(default)s)")
    ap.add_argument("--redo", action="store_true", help="run again what already has a result")

    g = ap.add_argument_group("generate")
    g.add_argument("--gen-jobs", type=int, default=2, help="parallel bugs (default: %(default)s)")
    g.add_argument("--model", help="Claude CLI model name (default: config.DEFAULT_MODEL)")
    g.add_argument("--fuzz-seconds", type=int, help="fuzzing per execution while the agent refines the test")
    g.add_argument("--max-llm-calls", type=int, help="the agent's budget of LLM calls per bug")

    e = ap.add_argument_group("evaluate")
    e.add_argument("--datasets", nargs="+", default=["apr"], choices=["apr"])
    e.add_argument("--suites", nargs="+", default=SUITES, choices=SUITES)
    e.add_argument("--poracle-container",
                   help="container that runs Poracle's own test (default: config.PORACLE_CONTAINER, Poracle's "
                        "original fuzzer; $PRESERVE_PORACLE_CONTAINER overrides it)")
    e.add_argument("--patches", nargs="+", help="only these patch IDs")
    e.add_argument("--duration", default="3m", help="differential fuzzing time per patch and suite (default: %(default)s)")
    e.add_argument("--repro-seconds", type=int, help="fresh fuzzing time of the repro check per patch")
    e.add_argument("--paired-inputs", type=int, help="inputs kept if a buggy version has to be fuzzed again")
    e.add_argument("--reps", type=int, default=1,
                   help="repetitions; repetition k uses Poracle's --base-seed k-1 (default: %(default)s)")
    e.add_argument("--no-gate", action="store_true", help="also use generated tests that are not valid")
    e.add_argument("--retry-unknown", action="store_true",
                   help="run again the (patch, suite, repetition) results that have no A / R verdict")
    return ap.parse_args()


# The package reads its directories from the environment when it is imported, so the experiment's
# own are set first: results, agent snapshots, and a container directory no other run uses.
ARGS = parse_args()
EXP = (REPO / ARGS.out).resolve()


def shown_path(path: Path) -> str:
    """A path as the report shows it: relative to the repository when it is inside it."""
    return str(path.relative_to(REPO)) if path.is_relative_to(REPO) else str(path)


os.environ["PRESERVE_RESULTS_DIR"] = str(EXP / "generation")
os.environ["PRESERVE_WORKSPACE_DIR"] = str(EXP / "workspace")
os.environ["PRESERVE_CONTAINER_WORK"] = (
    f"/tmp/preserve-experiment/{EXP.name}-{hashlib.sha256(str(EXP).encode()).hexdigest()[:8]}")
sys.path.insert(0, str(REPO / "src"))
from preserve import patchsets  # noqa: E402
from preserve.bugs import Bug  # noqa: E402
from preserve.config import (CONTAINER, CONTAINER_WORK, DEFAULT_FUZZ_SECONDS, DEFAULT_MAX_LLM_CALLS,  # noqa: E402
                             DEFAULT_MODEL, DEFAULT_PAIRED_INPUTS, DEFAULT_REPRO_SECONDS, PORACLE_CONTAINER,
                             REPORTS_DIR, RESULTS_DIR)
from preserve.container import Checkout, DockerEnv  # noqa: E402
from preserve.evaluate import (archive_paired, ensure_paired, evaluate_ours, evaluate_poracle,  # noqa: E402
                               poracle_env, stored_poracle_verdicts, supports_base_seed)

ARGS.poracle_container = ARGS.poracle_container or PORACLE_CONTAINER
SUITE_FILES = ("{suite}.json", "{suite}.poracle.log", "{suite}.statistics.json", "{suite}.error.json")

_LOG_LOCK = threading.Lock()


def say(message: str = "") -> None:
    """Print, and append to the experiment's log with the time."""
    with _LOG_LOCK:
        print(message, flush=True)
        with open(EXP / "experiment.log", "a") as f:
            f.write("".join(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {line}\n" for line in message.splitlines() or [""]))


def bug_key(bug: str) -> tuple[str, int]:
    return bug.split("-")[0], int(bug.split("-")[1])


def selected_bugs() -> list[str]:
    bugs = list(ARGS.bugs or [])
    if ARGS.bug_file:
        bugs += [l.strip() for l in Path(ARGS.bug_file).read_text().splitlines() if l.strip() and not l.startswith("#")]
    return sorted(set(bugs) or patchsets.experiment_bugs(), key=bug_key)


def record_invocation() -> None:
    def out(*cmd: str) -> str:
        p = subprocess.run(cmd, capture_output=True, text=True, cwd=REPO)
        return p.stdout.strip() if p.returncode == 0 else ""
    log = EXP / "experiment.json"
    runs = json.loads(log.read_text()) if log.exists() else []
    runs.append({"time": time.strftime("%Y-%m-%dT%H:%M:%S"), "argv": sys.argv[1:], "args": vars(ARGS),
                 "defaults": {"model": DEFAULT_MODEL, "fuzz_seconds": DEFAULT_FUZZ_SECONDS,
                              "max_llm_calls": DEFAULT_MAX_LLM_CALLS, "repro_seconds": DEFAULT_REPRO_SECONDS,
                              "paired_inputs": DEFAULT_PAIRED_INPUTS},
                 "git_commit": out("git", "rev-parse", "HEAD"), "git_dirty": bool(out("git", "status", "--porcelain")),
                 "claude_cli": out("claude", "--version"),
                 "container": CONTAINER, "container_image": out("docker", "inspect", "-f", "{{.Image}}", CONTAINER),
                 "container_work": CONTAINER_WORK,
                 "datasets": {d: hashlib.sha256((patchsets.dataset_dir(d) / "index.json").read_bytes()).hexdigest()
                              for d in patchsets.DATASETS if (patchsets.dataset_dir(d) / "index.json").exists()}})
    log.write_text(json.dumps(runs, indent=1) + "\n")


# --- generate -----------------------------------------------------------------------------------

def generation_result(bug: str) -> dict | None:
    f = RESULTS_DIR / bug / "result.json"
    return json.loads(f.read_text()) if f.exists() else None


def test_status(result: dict | None) -> str:
    """Where a bug's generated test stands: only a `valid` one is used on patches by default."""
    if result is None:
        return "not run"
    if result.get("no_report"):
        return "no bug report"
    if result.get("crashed"):
        return "crashed"
    if not result.get("success"):
        return "no test"
    if not result.get("derived_compiles"):
        return "not derived"
    if "valid" not in result:
        return "not validated"
    return "valid" if result["valid"] else "invalid"


def generate_one(bug: str, settings: dict) -> dict:
    """One bug, in a worker process. Never raises: a crash is the bug's result (and is retried by
    the next run of the stage)."""
    from preserve.pipeline import generate
    out = RESULTS_DIR / bug
    out.mkdir(parents=True, exist_ok=True)
    started = time.time()
    if not (REPORTS_DIR / f"{bug}.json").exists():
        result = {"bug": bug, "success": False, "no_report": True,
                  "failure_reason": "Defects4J lists no bug report for this bug, so the agent has no input"}
    else:
        try:
            result = generate(bug, **settings)
        except Exception as e:
            result = {**(generation_result(bug) or {}), "bug": bug, "success": False, "crashed": True,
                      "failure_reason": f"{type(e).__name__}: {e}"[:2000], "traceback": traceback.format_exc()[-4000:],
                      "total_seconds": round(time.time() - started, 1)}
        try:        # keep the paired inputs with the test; drop the checkouts, which nothing reuses
            result["paired_inputs_archived"] = archive_paired(bug)
            checkout = Checkout(Bug.parse(bug))
            checkout.env.exec_cmd(f"rm -rf {checkout.dir} {checkout.dir[:-1]}f {checkout.workdir}/fuzz-*", timeout=300)
        except Exception as e:
            result["cleanup_error"] = f"{type(e).__name__}: {e}"[:500]
    (out / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
    return result


def stage_generate() -> None:
    bugs = selected_bugs()
    todo = [b for b in bugs if ARGS.redo or test_status(generation_result(b)) in ("not run", "crashed")]
    settings = {"model": ARGS.model or DEFAULT_MODEL, "fuzz_seconds": ARGS.fuzz_seconds or DEFAULT_FUZZ_SECONDS,
                "max_llm_calls": ARGS.max_llm_calls or DEFAULT_MAX_LLM_CALLS}
    say(f"generate: {len(todo)} of {len(bugs)} bugs to do, {ARGS.gen_jobs} at a time; {settings}")
    started, cost = time.time(), 0.0
    with ProcessPoolExecutor(ARGS.gen_jobs) as pool:
        for n, f in enumerate(as_completed([pool.submit(generate_one, b, settings) for b in todo]), 1):
            r = f.result()
            cost += r.get("cost_usd") or 0
            status = test_status(r)
            why = (r.get("validation", {}).get("why_invalid") if status == "invalid" else
                   "" if status == "valid" else r.get("failure_reason") or r.get("derive_error") or "")
            say(f"[{n}/{len(todo)}] {r['bug']:9s} {status:14s} llm_calls={r.get('llm_call_count', 0)} "
                f"cost=${r.get('cost_usd') or 0:.2f} {r.get('total_seconds') or 0:.0f}s  {str(why)[:160]}")
    statuses = collections.Counter(test_status(generation_result(b)) for b in bugs)
    say(f"generate: done in {time.time() - started:.0f}s, ${cost:.2f} this run; tests of the {len(bugs)} bugs: "
        + ", ".join(f"{k} {v}" for k, v in statuses.most_common()))


# --- evaluate -----------------------------------------------------------------------------------

def result_file(dataset: str, rep: int, patch_id: str, suite: str) -> Path:
    return EXP / "evaluation" / dataset / f"rep{rep}" / patch_id / f"{suite}.json"


def has_result(dataset: str, rep: int, patch_id: str, suite: str) -> bool:
    """Is this run done? With --retry-unknown, only if it ended with a verdict."""
    f = result_file(dataset, rep, patch_id, suite)
    if not f.exists():
        return False
    row = json.loads(f.read_text())
    if suite == "poracle" and result_container(row) != ARGS.poracle_container:
        return False
    return not (ARGS.retry_unknown and row.get("verdict") not in ("A", "R"))


def result_container(row: dict) -> str:
    """The container a result was made in (results from before it was recorded: config.CONTAINER)."""
    return row.get("container") or row.get("preserve", {}).get("container") or CONTAINER


def supersede(out: Path, suite: str) -> None:
    """Move a suite's result made in another container to superseded/<container>/, kept, not used."""
    if not out.exists():
        return
    dest = out.parent / "superseded" / result_container(json.loads(out.read_text()))
    dest.mkdir(parents=True, exist_ok=True)
    for name in SUITE_FILES:
        f = out.parent / name.format(suite=suite)
        if f.exists():
            f.replace(dest / f.name)


def evaluate_one(env: DockerEnv, patch: patchsets.Patch, suite: str, rep: int, root: str, deltas: str,
                 statuses: dict[str, str]) -> dict:
    out = result_file(patch.dataset, rep, patch.id, suite)
    out.parent.mkdir(parents=True, exist_ok=True)
    if suite == "poracle" and out.exists() and result_container(json.loads(out.read_text())) != env.container:
        supersede(out, suite)
    row: dict = {"patch": patch.id, "dataset": patch.dataset, "bug": patch.bug, "tool": patch.tool,
                 "label": patch.label, "suite": suite, "rep": rep, "duration": ARGS.duration,
                 "started_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    try:
        if suite == "ours":
            row["test_status"] = statuses[patch.bug]
            row["repro_seconds"] = ARGS.repro_seconds or DEFAULT_REPRO_SECONDS
            row.update(evaluate_ours(env, patch, root, deltas, ARGS.duration, row["repro_seconds"],
                                     seed=rep - 1, keep=out.parent))
        else:
            row.update(evaluate_poracle(env, patch, root, ARGS.duration, seed=rep - 1, keep=out.parent))
    except Exception as e:      # recorded, and not saved as a result: the next run tries again
        row.update(verdict="?", error=f"{type(e).__name__}: {e}"[:2000], traceback=traceback.format_exc()[-4000:])
        (out.parent / f"{suite}.error.json").write_text(json.dumps(row, indent=1))
        return row
    out.write_text(json.dumps(row, indent=1, ensure_ascii=False))
    (out.parent / f"{suite}.error.json").unlink(missing_ok=True)
    return row


def stage_evaluate() -> None:
    bugs = set(selected_bugs())
    statuses = {b: test_status(generation_result(b)) for b in bugs}
    usable = {b for b, s in statuses.items() if s == "valid" or (ARGS.no_gate and s in ("invalid", "not validated"))}
    env = DockerEnv()                                   # ours: our fuzzer
    envs = {"ours": env, "poracle": poracle_env(ARGS.poracle_container)}
    for suite in ARGS.suites:
        if envs[suite].exec_cmd("true").returncode != 0:
            sys.exit(f"container {envs[suite].container} (suite {suite}) is not running; see config.PORACLE_CONTAINER "
                     "for how to start the original Poracle container")
    if "poracle" in ARGS.suites and ARGS.reps > 1 and not supports_base_seed(envs["poracle"]):
        sys.exit(f"the Poracle in {ARGS.poracle_container} has no --base-seed (the original always seeds with 0), "
                 "so its repetitions would all be the same run: use --reps 1 for the poracle suite")
    if "poracle" in ARGS.suites:
        say(f"evaluate: Poracle's test runs in {ARGS.poracle_container}"
            f"{' (original fuzzer)' if ARGS.poracle_container != CONTAINER else ' (OUR fuzzer changes)'}, "
            f"ours in {CONTAINER}")
    deltas = f"{CONTAINER_WORK}/deltas-ours-{os.getpid()}"      # own copies: parallel invocations do not collide
    env.exec_cmd(f"mkdir -p {CONTAINER_WORK} && rm -rf {deltas}")
    if "ours" in ARGS.suites and (RESULTS_DIR / "deltas").exists():
        subprocess.run(["docker", "cp", str(RESULTS_DIR / "deltas"), f"{CONTAINER}:{deltas}"], check=True)
    if "ours" in ARGS.suites:
        say(f"evaluate: our test is used for {len(usable)} of {len(bugs)} bugs "
            f"({', '.join(f'{k} {v}' for k, v in collections.Counter(statuses.values()).most_common())})")

    roots = []
    for dataset in ARGS.datasets:
        patches = [p for p in patchsets.load(dataset)
                   if p.bug in bugs and (not ARGS.patches or p.id in ARGS.patches)]
        root = f"{CONTAINER_WORK}/poracle-root-{dataset}-{os.getpid()}"      # the same path in each container
        for container in sorted({envs[s].container for s in ARGS.suites}):
            patchsets.copy_to_container(dataset, root, container)
        roots.append(root)
        if dataset == "apr" and patches:
            (EXP / "evaluation" / "apr").mkdir(parents=True, exist_ok=True)
            (EXP / "evaluation" / "apr" / "poracle_stored.json").write_text(
                json.dumps(stored_poracle_verdicts([p.id for p in patchsets.load("apr")]), indent=1))
        tasks = [(p, suite, rep) for rep in range(1, ARGS.reps + 1) for p in patches for suite in ARGS.suites
                 if (suite != "ours" or p.bug in usable)
                 and (ARGS.redo or not has_result(dataset, rep, p.id, suite))]
        say(f"evaluate {dataset}: {len(patches)} patches, {len(tasks)} runs to do ({' + '.join(ARGS.suites)}, "
            f"{ARGS.reps} repetition(s)), differential fuzzing {ARGS.duration}, {ARGS.jobs} at a time")
        started = time.time()
        with ThreadPoolExecutor(ARGS.jobs) as pool:
            need_paired = sorted({p.bug for p, suite, _ in tasks if suite == "ours"}, key=bug_key)
            for f in as_completed([pool.submit(lambda b: f"{b}: " + ensure_paired(
                    b, ARGS.repro_seconds or DEFAULT_REPRO_SECONDS, ARGS.paired_inputs or DEFAULT_PAIRED_INPUTS), b)
                    for b in need_paired]):
                say("  " + f.result())
            futures = [pool.submit(evaluate_one, envs[suite], p, suite, rep, root, deltas, statuses)
                       for p, suite, rep in tasks]
            for n, f in enumerate(as_completed(futures), 1):
                r = f.result()
                detail = (f"repro={r.get('repro', {}).get('result')} preserve={r.get('preserve', {}).get('verdict')}"
                          if r["suite"] == "ours" else f"preserve={r.get('preserve', {}).get('verdict')}")
                eta = (time.time() - started) / n * (len(tasks) - n)
                say(f"[{n}/{len(tasks)}] {dataset} rep{r['rep']} {r['bug']:9s} {r['patch']:36s} {r['label']:9s} "
                    f"{r['suite']:7s} {detail} -> {r['verdict']}  {r.get('seconds', 0):.0f}s  "
                    f"(about {eta / 60:.0f} min left) {r.get('error', '')[:200]}")
    env.exec_cmd(f"rm -rf {deltas} {' '.join(roots)}", timeout=600)
    if envs["poracle"].container != env.container and "poracle" in ARGS.suites:
        envs["poracle"].exec_cmd(f"rm -rf {' '.join(roots)}", timeout=600)


# --- report -------------------------------------------------------------------------------------

def span(seconds: float) -> str:
    """A duration for the report: minutes below an hour, hours above."""
    return f"{seconds / 60:.0f} min" if seconds < 3600 else f"{seconds / 3600:.1f} h"


def majority(verdicts: list[str | None]) -> tuple[str | None, str]:
    """The verdict of several repetitions (a tie counts as R: a difference was seen as often as
    not), and the count of each, e.g. 'A2 R1'."""
    counts = collections.Counter(v for v in verdicts if v in ("A", "R"))
    if not counts:
        return None, ""
    return ("R" if counts["R"] >= counts["A"] else "A"), " ".join(f"{k}{counts[k]}" for k in "AR" if counts[k])


VERSUS_FIX = {"C": "differs", "I": "same"}


def versus_fix(runs: list[dict]) -> str:
    """For the runs whose preservation test found a difference: does the patched version's output
    on the witness input differ from the developer fix's too? Poracle checks this itself
    (consistency C / I). "differs" points to the patch, "same" to the test: the patch then
    does what the developer fix does on an input the test says must be preserved."""
    found = {VERSUS_FIX.get(r["preserve"].get("consistency"), "unknown") for r in runs
             if r["preserve"].get("verdict") == "R" and r["preserve"].get("patch_plausible") is not False}
    return " / ".join(sorted(found))


def patch_rows(dataset: str, bugs: set[str], statuses: dict[str, str]) -> list[dict]:
    """One row per patch of the dataset: every suite's verdict over the repetitions done."""
    stored_file = EXP / "evaluation" / dataset / "poracle_stored.json"
    stored = json.loads(stored_file.read_text()) if stored_file.exists() else {}
    rows = []
    for p in patchsets.load(dataset):
        if p.bug not in bugs:
            continue
        runs: dict[str, list[dict]] = {s: [] for s in SUITES}
        for f in sorted((EXP / "evaluation" / dataset).glob(f"rep*/{p.id}/*.json")):
            if f.stem in SUITES:
                runs[f.stem].append(json.loads(f.read_text()))
        as_verdict = {"pass": "A", "fail": "R"}
        row = {"bug": p.bug, "patch": p.id, "tool": p.tool, "label": p.label, "test_status": statuses.get(p.bug, "not run"),
               "runs": runs}
        for key, verdicts in [
                ("ours_preserve", [r["preserve"].get("verdict") for r in runs["ours"]]),
                ("ours_repro", [as_verdict.get(r["repro"]["result"]) for r in runs["ours"]]),
                ("ours_combined", [r["verdict"] for r in runs["ours"]]),
                ("poracle", [r["verdict"] for r in runs["poracle"]])]:
            row[key], row[key + "_runs"] = majority(verdicts)
        row["poracle_stored"] = stored.get(p.id, {}).get("majority")
        row["poracle_container"] = " ".join(sorted({result_container(r) for r in runs["poracle"]}))
        # Poracle first runs the bug's trigger tests on the patch and rejects it without fuzzing
        # if one fails: such an R is the same whichever preservation test is used
        row["implausible"] = any(r["preserve"].get("patch_plausible") is False for s in SUITES for r in runs[s])
        row["ours_preserve_vs_fix"], row["poracle_vs_fix"] = versus_fix(runs["ours"]), versus_fix(runs["poracle"])
        row["seconds"] = {s: round(sum(r.get("seconds") or 0 for r in runs[s]), 1) for s in SUITES}
        rows.append(row)
    return sorted(rows, key=lambda r: (*bug_key(r["bug"]), r["label"], r["patch"]))


def score(rows: list[dict], key: str) -> dict:
    """How a kind of verdict does on labelled patches: right = R on Incorrect, A on Correct."""
    s = {"patches": len(rows), "no_verdict": sum(r[key] is None for r in rows)}
    for label, right in (("Incorrect", "R"), ("Correct", "A")):
        have = [r for r in rows if r["label"] == label and r[key]]
        s[label.lower()] = len(have)
        s[label.lower() + "_right"] = sum(r[key] == right for r in have)
    s["with_verdict"] = s["incorrect"] + s["correct"]
    s["right"] = s["incorrect_right"] + s["correct_right"]
    s["accuracy"] = round(s["right"] / s["with_verdict"], 4) if s["with_verdict"] else None
    return s


NAMES = {"ours_preserve": "ours: preserve", "ours_repro": "ours: repro", "ours_combined": "ours: repro + preserve",
         "poracle": "Poracle's test", "poracle_stored": "Poracle's test, its published runs"}


def score_table(rows: list[dict], keys: list[str]) -> tuple[str, dict]:
    lines = ["| tests | incorrect patches rejected | correct patches accepted | right | no verdict |", "|---|---|---|---|---|"]
    scores = {}
    for key in keys:
        s = scores[key] = score(rows, key)
        pct = f" ({100 * s['accuracy']:.1f}%)" if s["accuracy"] is not None else ""
        lines.append(f"| {NAMES[key]} | {s['incorrect_right']}/{s['incorrect']} | {s['correct_right']}/{s['correct']} | "
                     f"{s['right']}/{s['with_verdict']}{pct} | {s['no_verdict']} |")
    return "\n".join(lines), scores


def is_right(row: dict, key: str) -> bool:
    return row[key] == ("R" if row["label"] == "Incorrect" else "A")


def apr_report(rows: list[dict]) -> tuple[str, dict]:
    stats: dict = {"patches": len(rows)}
    md = [f"## APR patches (labelled): {len(rows)} patches of {len({r['bug'] for r in rows})} bugs\n",
          "A test is right on a patch when it rejects an Incorrect patch (R: the test fails) and accepts a Correct "
          "one (A: the test passes).\n"]
    scopes = [("valid generated tests", [r for r in rows if r["test_status"] == "valid"])]
    ungated = [r for r in rows if r["runs"]["ours"]]
    if any(r["test_status"] != "valid" for r in ungated):
        scopes.append(("all generated tests, valid or not", ungated))
    for name, scope in scopes:
        judged = [r for r in scope if r["ours_preserve"] and r["poracle"]]
        common = [r for r in judged if not r["implausible"]]
        table, scores = score_table(common, ["ours_preserve", "poracle"])
        agree = collections.Counter((is_right(r, "ours_preserve"), is_right(r, "poracle")) for r in common)
        md += [f"### Baseline comparison, preservation tests only ({name})\n",
               f"The {len(common)} patches ({len({r['bug'] for r in common})} bugs) that both our preservation test and "
               f"Poracle's test fuzzed and gave a verdict for. Left out: the patches that fail a trigger test of their "
               f"bug ({len(judged) - len(common)}). Poracle rejects those before any fuzzing, whichever preservation "
               "test it is given, so they say nothing about either test.\n", table, "",
               f"Both right {agree[(True, True)]}, only ours {agree[(True, False)]}, only Poracle's "
               f"{agree[(False, True)]}, neither {agree[(False, False)]}.\n"]
        stats[f"baseline ({name})"] = {**scores, "patches": len(common), "both_right": agree[(True, True)],
                                       "only_ours": agree[(True, False)], "only_poracle": agree[(False, True)],
                                       "neither": agree[(False, False)],
                                       "rejected_before_fuzzing": len(judged) - len(common)}
        both = [r for r in scope if r["ours_combined"]]
        table, scores = score_table(both, ["ours_repro", "ours_preserve", "ours_combined", "poracle"])
        md += [f"### Repro + preserve ({name})\n",
               f"The {len(both)} patches ({len({r['bug'] for r in both})} bugs) our tests gave a verdict for. Repro + "
               "preserve rejects a patch when the repro test fails on it or the preservation test finds a difference. "
               "The preserve rows include the patches rejected before fuzzing for failing a trigger test "
               f"({sum(r['implausible'] for r in both)}).\n", table, ""]
        stats[f"repro + preserve ({name})"] = {**scores, "patches": len(both),
                                               "rejected_before_fuzzing": sum(r["implausible"] for r in both)}
    table, scores = score_table(rows, ["ours_preserve", "ours_repro", "ours_combined", "poracle", "poracle_stored"])
    md += ["### All patches of the dataset\n",
           "Every patch; one whose bug has no usable generated test, or that was not run, has no verdict.\n", table, "",
           "### Per bug\n", "| bug | generated test | patches (incorrect / correct) | ours: preserve right | "
           "ours: repro + preserve right | Poracle's right |", "|---|---|---|---|---|---|"]
    stats["all patches"] = scores
    for bug in sorted({r["bug"] for r in rows}, key=bug_key):
        mine = [r for r in rows if r["bug"] == bug]
        cell = lambda key: (f"{sum(is_right(r, key) for r in mine if r[key])}/{sum(bool(r[key]) for r in mine)}"  # noqa: E731
                            if any(r[key] for r in mine) else "-")
        md.append(f"| {bug} | {mine[0]['test_status']} | {len(mine)} ({sum(r['label'] == 'Incorrect' for r in mine)} / "
                  f"{sum(r['label'] == 'Correct' for r in mine)}) | {cell('ours_preserve')} | {cell('ours_combined')} | "
                  f"{cell('poracle')} |")
    return "\n".join(md) + "\n", stats


def generation_report(bugs: list[str]) -> tuple[str, dict, list[dict]]:
    table = []
    for bug in bugs:
        r = generation_result(bug) or {}
        tokens = r.get("tokens") or {}
        v = r.get("validation") or {}
        table.append({
            "bug": bug, "status": test_status(r or None), "model": r.get("model", ""),
            "llm_calls": r.get("llm_call_count", 0), "test_versions": r.get("test_versions", ""),
            "cost_usd": r.get("cost_usd", 0), "tokens_input": tokens.get("input", 0),
            "tokens_output": tokens.get("output", 0), "tokens_cache_write": tokens.get("cache_write", 0),
            "tokens_cache_read": tokens.get("cache_read", 0),
            "seconds_total": r.get("total_seconds", ""), "seconds_llm": r.get("llm_seconds", ""),
            **{f"seconds_{k}": (r.get("timing") or {}).get(k, "") for k in ("checkout", "agent", "derive", "validation")},
            "failing_test": r.get("failing_test", ""), "test_file": r.get("test_file", ""),
            "why": (v.get("why_invalid") or r.get("failure_reason") or r.get("derive_error") or "")[:300]})
    statuses = collections.Counter(t["status"] for t in table)
    total = lambda key: round(sum(t[key] or 0 for t in table), 2)       # noqa: E731
    stats = {"bugs": len(bugs), "status": dict(statuses), "cost_usd": total("cost_usd"), "llm_calls": total("llm_calls"),
             "tokens": {k: total("tokens_" + k) for k in ("input", "output", "cache_write", "cache_read")},
             "seconds": total("seconds_total"), "llm_seconds": total("seconds_llm")}
    md = [f"## Test generation: {len(bugs)} bugs\n",
          "| generated test | bugs |", "|---|---|", *[f"| {k} | {v} |" for k, v in statuses.most_common()], "",
          f"LLM calls {stats['llm_calls']:.0f}, cost ${stats['cost_usd']:.2f}, tokens {stats['tokens']}, "
          f"time {span(stats['seconds'])} (of which the LLM {span(stats['llm_seconds'])}). "
          "Per bug: report/generation.csv.\n",
          "valid: every input inside buggyIf fails on the buggy version and passes on the developer fix. Only valid "
          "tests are used on patches unless the evaluation ran with --no-gate.\n"]
    not_valid = [t for t in table if t["status"] not in ("valid", "not run")]
    if not_valid:
        md += ["| bug | generated test | why |", "|---|---|---|",
               *[f"| {t['bug']} | {t['status']} | {' '.join(t['why'].split())[:200]} |" for t in not_valid], ""]
    return "\n".join(md) + "\n", stats, table


def write_csv(path: Path, rows: list[dict], columns: list[str]) -> None:
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def stage_report() -> None:
    bugs = selected_bugs()
    out = EXP / "report"
    out.mkdir(parents=True, exist_ok=True)
    statuses = {b: test_status(generation_result(b)) for b in bugs}
    md, gen_stats, gen_table = generation_report(bugs)
    write_csv(out / "generation.csv", gen_table, list(gen_table[0]) if gen_table else ["bug"])
    stats: dict = {"experiment": shown_path(EXP), "time": time.strftime("%Y-%m-%dT%H:%M:%S"), "generation": gen_stats}
    parts = [f"# Experiment {EXP.name}\n", f"Written {stats['time']} from `{shown_path(EXP)}`.\n", md]
    columns = ["bug", "patch", "tool", "label", "test_status", "ours_preserve", "ours_preserve_runs", "ours_repro",
               "ours_repro_runs", "ours_combined", "ours_combined_runs", "poracle", "poracle_runs", "poracle_container",
               "poracle_stored",
               "implausible", "ours_preserve_vs_fix", "poracle_vs_fix", "seconds_ours",
               "seconds_poracle"]
    for dataset in patchsets.DATASETS:
        if not (patchsets.dataset_dir(dataset) / "index.json").exists():
            continue
        rows = patch_rows(dataset, set(bugs), statuses)
        write_csv(out / f"{dataset}_patches.csv",
                  [{**r, "seconds_ours": r["seconds"]["ours"], "seconds_poracle": r["seconds"]["poracle"]} for r in rows], columns)
        seconds = {s: round(sum(r["seconds"][s] for r in rows), 1) for s in SUITES}
        md, st = apr_report(rows)
        containers = dict(collections.Counter(result_container(r) for row in rows for r in row["runs"]["poracle"]))
        stats[dataset] = {**st, "evaluation_seconds": seconds, "poracle_containers": containers,
                          "runs": {s: sum(len(r["runs"][s]) for r in rows) for s in SUITES}}
        where = ", ".join(f"{c} ({'our fuzzer changes' if c == CONTAINER else 'original fuzzer'}) {n}"
                          for c, n in containers.items())
        parts += [md, f"Runs done: {stats[dataset]['runs']}; machine time {span(seconds['ours'])} (ours), "
                      f"{span(seconds['poracle'])} (Poracle's). Per patch: report/{dataset}_patches.csv.\n",
                  f"Poracle's test ran in: {where or '-'}. Ours ran in {CONTAINER} (our fuzzer changes).\n"]
    (out / "stats.json").write_text(json.dumps(stats, indent=1) + "\n")
    (out / "summary.md").write_text("\n".join(parts))
    say("\n".join(parts))
    say(f"report: {out}/summary.md, stats.json, generation.csv, *_patches.csv")


def main() -> None:
    EXP.mkdir(parents=True, exist_ok=True)
    record_invocation()
    say(f"=== {ARGS.stage}: experiment {EXP} (container directory {CONTAINER_WORK}) ===")
    if ARGS.stage in ("generate", "all"):
        stage_generate()
    if ARGS.stage in ("evaluate", "all"):
        stage_evaluate()
    if ARGS.stage in ("report", "all"):
        stage_report()


if __name__ == "__main__":
    main()
