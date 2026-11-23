"""Checking one patch, with our generated test and with Poracle's own test.

    ours      repro     the repro method on the patched version (repro.check_version):
                        pass / fail / unknown
              preserve  the derived preservation method, Poracle's differential fuzzer on buggy
                        vs patched: A (no difference outside buggyIf) / R
              verdict   R if repro fails or preserve rejects
    poracle   preserve  Poracle's hand-written test, same duration and seed, on Poracle's ORIGINAL
                        fuzzer (config.PORACLE_CONTAINER, made from the original image): A / R

Our suite runs in config.CONTAINER, which has our fuzzer changes (scripts/install_fuzzer.py);
Poracle's suite runs in a container without them, so it is Poracle as published. Each result
records the container it ran in.

Poracle is run from a directory of our own in the container that holds the dataset's configs/
and patches/ (it reads patches/<ID> next to its work directory), so a patch from any dataset is
handled like Poracle's own.
"""

import collections
import json
import re
import subprocess
import time
from pathlib import Path

from preserve.bugs import Bug
from preserve.config import CONTAINER, RESULTS_DIR
from preserve.container import ORIGINAL_STARTUP, PORACLE_EXP, Checkout, DockerEnv
from preserve.patchsets import Patch
from preserve.repro import check_version, fuzz_buggy, paired_dir, paired_outcomes

PORACLE_DELTAS = f"{PORACLE_EXP}/deltas"        # Poracle's hand-written tests, as shipped in the container
PAIRED_ARCHIVE = "paired-inputs.tar.gz"         # the bug's paired inputs, kept with its generated test


def duration_seconds(duration: str) -> int:
    """'3m' -> 180 (Poracle's --duration: a number with s, m or h)."""
    m = re.fullmatch(r"(\d+)([smh]?)", duration)
    if not m:
        raise ValueError(f"bad duration: {duration}")
    return int(m.group(1)) * {"": 1, "s": 1, "m": 60, "h": 3600}[m.group(2)]


def combined(preserve_verdict: str | None, repro_result: str) -> str:
    if preserve_verdict == "R" or repro_result == "fail":
        return "R"
    return "A" if preserve_verdict == "A" else "?"


def recover_judge(env: DockerEnv, work: str) -> dict | None:
    """Poracle reaches its verdict (Judge) right after fuzzing, but only saves it at the very end.
    If a later step fails -- typically compiling OUR test against the developer-fixed version,
    which may change an API the test uses (Math-8's fix changes sample()'s return type) -- the
    verdict is lost. Recover it from the fuzzing output with Poracle's own rule (zest_patch):
    a target_hit dir that exists but is empty -> no diff; otherwise a non-empty diff_out -> diff.
    Only when the log shows that fuzzing finished and the patch passed the original test."""
    log = env.exec_cmd(f"cat {work}/poracle.log 2>/dev/null").stdout
    if "Run zest_patch" not in log or "Check out (fix)" not in log or "patch test failed" in log:
        return None
    r = env.exec_cmd(
        f"for t in {work}/fuzz-results/*/*; do "
        f'th=$(ls "$t/target_hit" 2>/dev/null | wc -l); '
        f'hd=$([ -d "$t/target_hit" ] && echo 1 || echo 0); '
        f'do=$(ls "$t/diff_out" 2>/dev/null | wc -l); echo "$hd $th $do"; done')
    diff = False
    for line in r.stdout.strip().splitlines():
        if len(line.split()) != 3:
            continue
        hd, th, do = (int(x) for x in line.split())
        diff |= (do > 0) if not (hd and th == 0) else False
    reason = ("compiling the developer-fixed version failed" if "compiling patch src failed" in log
              else "a step after fuzzing failed")
    return {"verdict": "R" if diff else "A", "consistency": None, "patch_plausible": True,
            "verdict_source": f"recovered from fuzz-results ({reason}; not validated against the fix)"}


def poracle_env(container: str) -> DockerEnv:
    """Where Poracle's own test runs. Our fuzzer's container keeps its own environment; any other
    (the original image) gets Poracle's environment only."""
    return DockerEnv(container) if container == CONTAINER else DockerEnv(container, startup=ORIGINAL_STARTUP)


_BASE_SEED: dict[str, bool] = {}


def supports_base_seed(env: DockerEnv) -> bool:
    """Does this container's Poracle take --base-seed? The original does not: it always seeds its
    random number generator with 0 (the flag came with the condition-aware fuzzer)."""
    if env.container not in _BASE_SEED:
        _BASE_SEED[env.container] = env.exec_cmd(f"grep -q -- --base-seed {PORACLE_EXP}/src/main.py").returncode == 0
    return _BASE_SEED[env.container]


def run_poracle(env: DockerEnv, root: str, patch_id: str, duration: str, deltas: str, tag: str,
                seed: int = 0, only_first_test: bool = False, keep: Path | None = None) -> dict:
    """Run `poracle configs/<patch_id>.json` from `root` with the tests in `deltas`.

    only_first_test  our delta has a preservation method for the bug's first trigger test only
                     (Poracle's test1), so its other test units are skipped
    keep             host directory for Poracle's log and statistics.json (<tag>.poracle.log, ...)

    Returns verdict (A / R / None), consistency (Poracle's own validation against the developer
    fix: C when its judgement matches the patch's label), patch_plausible, the test results with
    the witness of a difference (the input and the buggy, patched and fixed outputs), and times."""
    work = f".poracle-{tag}-{patch_id}-s{seed}"     # repetitions of a patch can run at the same time
    skip = "--skip-tests " + " ".join(str(i) for i in range(2, 100)) + " " if only_first_test else ""
    if supports_base_seed(env):
        seed_arg = f"--base-seed {seed} "
    elif seed == 0:
        seed_arg = ""                       # the original Poracle seeds with 0 itself
    else:
        raise ValueError(f"the Poracle in {env.container} has no --base-seed: only repetition 1 (seed 0) can run there")
    started = time.time()
    r = env.exec_cmd(f"cd {root} && poracle configs/{patch_id}.json --duration {duration} {seed_arg}"
                     f"{skip}--deltas-dir {deltas} --work-dir {work}",
                     timeout=4 * duration_seconds(duration) + 1800)
    row: dict = {"container": env.container, "deltas": deltas, "duration": duration, "seed": seed,
                 "returncode": r.returncode, "verdict": None, "seconds": round(time.time() - started, 1)}
    try:
        raw = env.read(f"{root}/{work}/statistics.json")
        st = json.loads(raw)
        row.update(verdict=st.get("verdict"), consistency=st.get("consistency"),
                   patch_plausible=st.get("patch_plausible"), total_time=st.get("total_time"),
                   time=st.get("time"), test_results=st.get("test_results"))
        for tr in st.get("test_results") or []:      # keep the witness, if any
            for key in ("in", "org_out", "patch_out", "fix_out"):
                if tr.get(key):
                    try:
                        tr[key + "_content"] = env.read(
                            tr[key].replace("/.poracle/", f"/{work}/")).decode(errors="replace")[:2000]
                    except FileNotFoundError:
                        pass
        if keep:
            (keep / f"{tag}.statistics.json").write_bytes(raw)
    except (FileNotFoundError, json.JSONDecodeError):
        row["error"] = r.stdout[-1500:]
    if row.get("verdict") in (None, "UNKNOWN"):
        row.update(recover_judge(env, f"{root}/{work}") or {"verdict": None})
    if keep:
        try:
            (keep / f"{tag}.poracle.log").write_bytes(env.read(f"{root}/{work}/poracle.log"))
        except FileNotFoundError:
            (keep / f"{tag}.poracle.log").write_text(r.stdout)
    env.exec_cmd(f"rm -rf {root}/{work}")
    return row


# --- the bug's paired inputs ----------------------------------------------------------------

def archive_paired(bug: str) -> bool:
    """Copy the bug's paired inputs out of the container, next to its generated test."""
    if not paired_outcomes(bug):
        return False
    workdir = Checkout(Bug.parse(bug)).workdir
    with open(RESULTS_DIR / bug / PAIRED_ARCHIVE, "wb") as f:
        p = subprocess.run(["docker", "exec", CONTAINER, "tar", "-C", workdir, "-czf", "-", "paired-inputs"], stdout=f)
    return p.returncode == 0


def ensure_paired(bug: str, seconds: int, max_inputs: int) -> str:
    """Make sure the bug's paired inputs are in the container: there already, restored from the
    archive, or made again by fuzzing the buggy version. Returns what was done."""
    if paired_outcomes(bug):
        return "paired inputs present"
    archive = RESULTS_DIR / bug / PAIRED_ARCHIVE
    workdir = Checkout(Bug.parse(bug)).workdir
    if archive.exists() and (RESULTS_DIR / bug / "paired.json").exists():
        with open(archive, "rb") as f:
            subprocess.run(["docker", "exec", "-i", CONTAINER, "bash", "-c",
                            f"rm -rf {paired_dir(bug)} && mkdir -p {workdir} && tar -C {workdir} -xzf -"], stdin=f)
        if paired_outcomes(bug):
            return "paired inputs restored from the archive"
    again = fuzz_buggy(bug, seconds, max_inputs)
    archive_paired(bug)
    return f"buggy version fuzzed again: {again.get('outcomes', again.get('why'))}"


# --- one patch ------------------------------------------------------------------------------

def evaluate_ours(env: DockerEnv, patch: Patch, root: str, deltas: str, duration: str, repro_seconds: int,
                  seed: int = 0, keep: Path | None = None) -> dict:
    """Our generated test on one patch: the repro check, then the preservation check."""
    started = time.time()
    row: dict = {}
    patched = Checkout(Bug.parse(patch.bug), patch=patch.id, patches_dir=f"{root}/patches", suffix=f"-s{seed}")
    try:
        patched.prepare()
        row["repro"] = check_version(patched, patch.bug, repro_seconds)
    except Exception as e:      # the patched version could not be built: only preserve can run
        row["repro"] = {"result": "unknown", "why": f"{type(e).__name__}: {e}"[:800]}
    row["repro"]["seconds"] = round(time.time() - started, 1)
    patched.remove()
    env.exec_cmd(f"rm -rf {patched.dir}-fuzz")
    row["preserve"] = run_poracle(env, root, patch.id, duration, deltas, "ours", seed, only_first_test=True, keep=keep)
    row["verdict"] = combined(row["preserve"].get("verdict"), row["repro"]["result"])
    row["seconds"] = round(time.time() - started, 1)
    return row


def evaluate_poracle(env: DockerEnv, patch: Patch, root: str, duration: str, seed: int = 0,
                     keep: Path | None = None) -> dict:
    """Poracle's own hand-written test on one patch, in `env` (poracle_env(config.PORACLE_CONTAINER):
    the original fuzzer)."""
    row = {"container": env.container,
           "preserve": run_poracle(env, root, patch.id, duration, PORACLE_DELTAS, "poracle", seed, keep=keep)}
    row["verdict"] = row["preserve"].get("verdict") if row["preserve"].get("verdict") in ("A", "R") else "?"
    row["seconds"] = row["preserve"]["seconds"]
    return row


def stored_poracle_verdicts(patch_ids: list[str]) -> dict[str, dict]:
    """Poracle's own published runs for its patches (all_results/*/<run>/<Patch>/statistics.json in
    the container, 10 runs per set): {patch: {"majority": A/R, "runs": {verdict: count}}}."""
    script = """
import json, glob, collections, sys
want = set(json.loads(sys.stdin.read()))
out = collections.defaultdict(list)
for f in glob.glob('/poracle-experiments/all_results/*/*/*/statistics.json'):
    pid = f.split('/')[-2]
    if pid in want:
        try:
            out[pid].append(json.load(open(f)).get('verdict'))
        except Exception:
            pass
print(json.dumps(out))
"""
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "python3", "-c", script], input=json.dumps(patch_ids),
                       capture_output=True, text=True)
    if p.returncode != 0:
        return {}
    stored = {}
    for pid, verdicts in json.loads(p.stdout).items():
        counts = collections.Counter(v for v in verdicts if v in ("A", "R"))
        if counts:      # a tie counts as R, as everywhere else in the experiment
            stored[pid] = {"majority": "R" if counts["R"] >= counts["A"] else "A", "runs": dict(counts)}
    return stored
