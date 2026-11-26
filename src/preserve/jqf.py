"""Fuzzing a JQF test in the container and reading what happened.

Two ways to run a test on one version of the code:
  fuzz     JQF's coverage-guided fuzzer (Zest, `jqf-zest`) for a number of seconds; it saves
           the inputs it tried
  replay   JQF's ReproDriver on saved inputs. A saved input is the random bytes the arguments
           were generated from, so a replay runs the test on exactly the same arguments, on
           whichever version it is given

Poracle's JQF prints every trial's arguments (`in:` lines, values separated by `in: ;`) and the
test's Log.logOutIf(true, ...) prints the logged values (`out:` lines); a trial with only `in:`
lines was rejected by assumeTrue.

Both build the java command themselves rather than using JQF's jqf-zest / jqf-repro scripts:
the project's classes come first on the classpath (jqf-fuzz's manifest pulls in a commons-lang3
that would otherwise shadow the code under test in Lang bugs), and ReproDriver gets `--cp`,
which it needs to build its instrumenting class loader.
"""

import re
import textwrap
from dataclasses import dataclass, field

from preserve.container import DockerEnv

TOO_STRONG = "Assumption is too strong"       # JQF aborts when >90% of the inputs are discarded
_EXC_HEADER = re.compile(r"^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)(?::\s?(.*))?$")
_PROGRESS = re.compile(r"^[.E]+$")            # JUnit progress marks


@dataclass
class JqfRun:
    """The outcome of one run. Trials are grouped by a key: "" for the trials that passed, and
    the exception class (fuzz) or shortened stack trace (parse_jqf_output) for failing ones."""

    compile_errors: str | None = None         # set: the test did not compile, nothing ran
    output: str = ""                          # raw merged stdout+stderr
    exit_code: int | None = None
    groups: dict[str, list[str]] = field(default_factory=dict)  # key -> example trials, as text
    counts: dict[str, int] = field(default_factory=dict)        # key -> number of trials
    full_traces: dict[str, str] = field(default_factory=dict)   # key -> one full stack trace
    errors: dict[str, str] = field(default_factory=dict)        # key -> message + trace to show
    outcomes: dict[str, str] = field(default_factory=dict)      # fuzz(keep=): kept input file -> outcome
    trials: int = 0
    discarded: int = 0                        # rejected by assumeTrue
    too_strong: bool = False                  # JQF's random driver gave up (parse_jqf_output)
    guidance_error: str = ""                  # fuzzing stopped, e.g. a generator threw
    valid: int = 0                            # trials that passed assumeTrue
    features: dict | None = None              # llm_features.json dumped by the collector (see modify.md)

    @property
    def passing(self) -> list[str]:
        return self.groups.get("", [])

    @property
    def failing(self) -> dict[str, list[str]]:
        return {k: v for k, v in self.groups.items() if k}

    @property
    def n_passing(self) -> int:
        return self.counts.get("", len(self.passing))

    @property
    def n_failing(self) -> int:
        return sum(self.counts.get(k, len(v)) for k, v in self.failing.items())

    def show_passing(self, max_show: int = 2) -> str:
        return "\n    ".join(self.passing[-max_show:])

    def show_failing(self, max_show: int = 2, max_groups: int = 5) -> str:
        parts = []
        for key in list(self.groups)[:max_groups]:
            if not key:
                continue
            location = _location(self.full_traces.get(key, key))
            location = f"\nLocation: {location}\n" if location else ""
            parts.append(f"The error:\n{textwrap.indent(self.errors.get(key, key), ' ' * 4)}\n{location}\n"
                         "is triggered by the following inputs:\n\n"
                         + textwrap.indent("\n".join(self.groups[key][-max_show:]), " " * 4))
        return "\n\n".join(parts)

    def summary(self) -> dict:
        return {"trials": self.trials, "discarded_by_assume": self.discarded, "passing": self.n_passing,
                "failing_by_error": {k.splitlines()[0]: self.counts.get(k, len(v))
                                     for k, v in self.failing.items()}}


def parse_jqf_output(output: str, test_class_simple: str, exit_code: int | None = None) -> JqfRun:
    """Group the trials in JQF's printed output (in:/out: lines and stack traces)."""
    trials: list[dict] = []
    cur: dict | None = None
    in_sep = out_sep = in_trace = False

    for line in output.splitlines():
        if line.startswith("Fuzzing stopped due to guidance exception") or line.startswith("Time: "):
            break                       # JQF abort message / JUnit's summary block
        if _PROGRESS.match(line):
            continue
        stripped = line.lstrip(".")
        if stripped.startswith("in: "):
            value = stripped[4:]
            if value == ";":
                in_sep = True
                continue
            if cur is not None and in_sep and not cur["out"] and not cur["trace"]:
                cur["in"].append(value)                     # next argument of this trial
            else:
                cur = {"in": [value], "out": [], "trace": []}
                trials.append(cur)
            in_sep = out_sep = in_trace = False
            continue
        if cur is None:
            continue
        if line.startswith("out: "):
            value = line[5:]
            if value == ";":
                out_sep = True
                continue
            if cur["out"] and not out_sep:
                cur["out"][-1] += "\n" + value
            else:
                cur["out"].append(value)
            out_sep = False
            continue
        if in_trace and line.startswith(("\tat ", "Caused by:", "\t... ", " ")):
            cur["trace"].append(line.rstrip())
            continue
        if not cur["trace"] and _is_throwable_header(line):
            cur["trace"].append(line.rstrip())
            in_trace = True
            continue
        in_trace = False

    run = JqfRun(output=output, exit_code=exit_code, trials=len(trials), too_strong=TOO_STRONG in output)
    for t in trials:
        if not t["out"] and not t["trace"]:
            run.discarded += 1
            continue
        key = "\n".join(_short_trace(t["trace"], test_class_simple))
        if key not in run.groups:
            run.groups[key] = []
            if t["trace"]:
                run.full_traces[key] = "\n".join(t["trace"])
        run.groups[key].append(f"input ({', '.join(t['in'])}) -> logged [{', '.join(t['out'])}]")
    stop = re.search(r"Fuzzing stopped due to guidance exception: (.*)", output)
    if stop and not run.too_strong:
        run.guidance_error = stop.group(1).strip()
    return run


def exception_types(trace: str) -> list[str]:
    """Exception class names in a Java stack trace, in order of appearance."""
    found: list[str] = []
    for m in re.finditer(r"^([\w.$]+(?:Error|Exception|Failure))(?::|$)", trace, re.MULTILINE):
        if m.group(1) not in found:
            found.append(m.group(1))
    return found


# JUnit's assertion failures (all subclasses of java.lang.AssertionError)
_ASSERTION_TYPES = {"AssertionError", "ComparisonFailure", "ArrayComparisonFailure", "AssertionFailedError"}


def is_assertion_failure(exception_type: str) -> bool:
    return exception_type.split(".")[-1] in _ASSERTION_TYPES


def _is_throwable_header(line: str) -> bool:
    m = _EXC_HEADER.match(line)
    return bool(m) and m.group(1).rsplit(".", 1)[-1].endswith(("Error", "Exception", "Failure", "Throwable"))


def _short_trace(trace: list[str], test_class_simple: str, max_lines: int = 15) -> list[str]:
    """The trace up to the test method's own frame; JQF/JUnit runner frames below it are noise."""
    out: list[str] = []
    for line in trace:
        out.append(line)
        if test_class_simple and test_class_simple in line and line.strip().startswith("at "):
            break
    return out[:max_lines]


def _location(trace: str) -> str | None:
    """The first stack frame outside JUnit's assertion classes."""
    if not _EXC_HEADER.match(trace.splitlines()[0] if trace else ""):
        return None
    frames = [f for f in re.findall(r"^\s+at (\S+)", trace, re.MULTILINE)
              if not f.startswith(("org.junit.", "junit."))]
    return frames[0] if frames else None


# --- fuzz and replay ---------------------------------------------------------------------

JQF_HOME = "/poracle/modules/JQF"
_INST = f"{JQF_HOME}/instrument/target"
_FUZZ = f"{JQF_HOME}/fuzz/target"


def _jqf_java(classpath: str, main: str, jvm_opts: str = "") -> str:
    """The java command of JQF's scripts/jqf-driver.sh, but with the project's classes FIRST on
    the classpath. JQF's jar lists its own dependencies in its manifest (among them
    commons-lang3-3.11), and the script puts that jar first, which shadows the code under test
    for the Lang bugs in org.apache.commons.lang3."""
    return (f"java -ea -Xbootclasspath/a:{_INST}/classes:{_INST}/jqf-instrument-1.5-SNAPSHOT.jar:"
            f"{_INST}/dependency/asm-7.2.jar -javaagent:{_INST}/jqf-instrument-1.5-SNAPSHOT.jar "
            f"-Djanala.conf={JQF_HOME}/scripts/janala.conf "
            f'-cp "{classpath}:{_FUZZ}/classes:{_FUZZ}/jqf-fuzz-1.5-SNAPSHOT.jar:$CLASSPATH" {jvm_opts} {main}')

_COUNT_TRIALS = ("awk '/^\\.*in: ;/{s++;next} /^\\.*in: /{i++} /^out: ;/{t++;next} /^out: /{o++} "
                 "END{print \"COUNTS\", i-s, o-t}'")     # trials started, trials that reached the log line
_REPLAY_RESULT = re.compile(r"^(id_\d+) ::= (\w+)(?: \((.*)\))?$", re.MULTILINE)


def install_test(env: DockerEnv, source: str, test_path: str, compile_fn, log=None) -> str | None:
    """Write the test file and compile it; returns the compile errors, or None."""
    env.write(test_path, source.encode())
    ok, errors = compile_fn()
    if log:
        log(f"COMPILE {'OK' if ok else 'FAILED'}", errors)
    return None if ok else errors


def fuzz(env: DockerEnv, test_class: str, test_method: str, classpath: str, out_dir: str, seconds: int,
         classify: int | None = 0, keep: tuple[str, int] | None = None, log=None) -> JqfRun:
    """Fuzz an already compiled test with Zest for `seconds`, then see what the inputs did.

    Zest only reports unique failures, so the inputs it tried are saved and replayed:
      classify  how many of the tried inputs to replay and count by outcome (None: all, 0: only
                the unique failures Zest saved). They are taken evenly from the whole run.
      keep      (directory, max): keep the classified inputs that passed assumeTrue there, with
                their outcomes in JqfRun.outcomes, for replaying on another version.
    The returned JqfRun has every input tried in `trials`, the inputs that passed assumeTrue in
    `valid`, and the classified ones in `counts`, with up to three example inputs per outcome
    (and, for a failure, its message and stack trace)."""
    save_all = classify != 0
    # tier-feedback integration (see modify.md): enable the LLMFeatureCollector
    # on every Zest run and pin its JSON output to this run's out_dir. The
    # property is harmless when the collector isn't installed on the JVM.
    _feedback_props = f"-Dllmfeedback.on=true -Dllmfeedback.out={out_dir}"
    zest = _jqf_java(classpath, "edu.berkeley.cs.jqf.fuzz.ei.ZestDriver",
                     (f"-Djqf.ei.SAVE_ALL_INPUTS=true {_feedback_props}" if save_all
                      else _feedback_props))
    # Zest has no time limit of its own here, so `timeout` stops it; its output stays in out_dir
    r = env.exec_cmd(
        f"rm -rf {out_dir} {out_dir}.out\n"
        f"timeout -k 5 {seconds} {zest} {test_class} {test_method} {out_dir} > {out_dir}.out 2>&1\n"
        f"{_COUNT_TRIALS} {out_dir}.out; grep -m1 'Fuzzing stopped due to guidance exception' {out_dir}.out\n"
        f"rm -f {out_dir}.out", timeout=seconds + 120)
    run = JqfRun(output=r.stdout)
    if counts := re.search(r"^COUNTS (-?\d+) (-?\d+)$", r.stdout, re.MULTILINE):
        run.trials, run.valid = int(counts.group(1)), int(counts.group(2))
        run.discarded = run.trials - run.valid
    if stop := re.search(r"Fuzzing stopped due to guidance exception: (.*)", r.stdout):
        run.guidance_error = stop.group(1).strip()

    inputs = f"{out_dir}/all" if save_all else f"{out_dir}/failures"
    if classify:        # an even sample of the saved inputs
        env.exec_cmd(f"cd {inputs} && n=$(ls | wc -l) && [ $n -gt {classify} ] && "
                     f"ls | awk -v s=$((n / {classify})) 'NR % s != 0' | xargs -r rm -f", timeout=300)
    outcomes = replay(env, test_class, test_method, classpath, inputs)
    by_outcome: dict[str, list[str]] = {}
    for f in sorted(outcomes):
        if outcomes[f] != "INVALID":
            by_outcome.setdefault(outcomes[f], []).append(f)
    examples = replay_examples(env, test_class, test_method, classpath, inputs,
                               [f for files in by_outcome.values() for f in files[-3:]])
    for outcome, files in by_outcome.items():
        key = "" if outcome == "SUCCESS" else outcome
        run.counts[key] = len(files)
        run.groups[key] = [examples[f][0] for f in files[-3:] if f in examples]
        trace = next((examples[f][1] for f in files[-3:] if f in examples and examples[f][1]), "")
        if key and trace:
            run.full_traces[key] = trace
            run.errors[key] = "\n".join(_short_trace(trace.splitlines(), test_class.rsplit(".", 1)[-1]))
    if keep:
        keep_dir, keep_max = keep
        kept = sorted(f for files in by_outcome.values() for f in files)
        kept = kept[::max(1, len(kept) // keep_max)][:keep_max]
        env.write(f"{out_dir}/keep", "\n".join(kept).encode())
        env.exec_cmd(f"rm -rf {keep_dir} && mkdir -p {keep_dir} && cd {inputs} && "
                     f"xargs -r mv -t {keep_dir} < {out_dir}/keep", timeout=600)
        run.outcomes = {f: outcomes[f] for f in kept}
    # tier-feedback (see modify.md): read features.json before out_dir is deleted
    try:
        ls_out = env.exec_cmd(f"ls -la {out_dir}/ 2>/dev/null | head -20; "
                              f"stat {out_dir}/llm_features.json 2>&1 | head -5", timeout=10).stdout
        if log: log("FEATURES PATH DEBUG", ls_out)
        features_blob = env.exec_cmd(
            f"cat {out_dir}/llm_features.json 2>/dev/null || true", timeout=10).stdout
        if features_blob.strip():
            import json as _json
            run.features = _json.loads(features_blob)
    except Exception as e:
        if log: log("FEATURES READ EXC", repr(e))
        run.features = None
    env.exec_cmd(f"rm -rf {out_dir}", timeout=300)
    if log:
        log(f"FUZZ ({test_method}, {seconds}s)", {**run.summary(), "passed_assume": run.valid,
                                                    "classified": sum(run.counts.values()),
                                                    "features_schema": (run.features or {}).get("schema", "MISSING")})
    return run


def _replay_cmd(test_class: str, test_method: str, classpath: str) -> str:
    driver = _jqf_java(classpath, "edu.berkeley.cs.jqf.fuzz.repro.ReproDriver")
    return f"xargs -r -n 2000 {driver} --cp {classpath} {test_class} {test_method}"


def replay(env: DockerEnv, test_class: str, test_method: str, classpath: str, inputs_dir: str,
           timeout: int = 1800) -> dict[str, str]:
    """Run the test on every saved input in `inputs_dir`. Returns {file: outcome}; an outcome is
    SUCCESS, INVALID (rejected by assumeTrue), TIMEOUT or the exception class."""
    r = env.exec_cmd(f"ls {inputs_dir} 2>/dev/null | sed 's#^#{inputs_dir}/#' | "
                     f"({_replay_cmd(test_class, test_method, classpath)}) 2>&1 | grep ' ::= '", timeout=timeout)
    return {m.group(1): m.group(3) or m.group(2) for m in _REPLAY_RESULT.finditer(r.stdout)}


def replay_examples(env: DockerEnv, test_class: str, test_method: str, classpath: str, inputs_dir: str,
                    files: list[str]) -> dict[str, tuple[str, str]]:
    """Replay a few inputs for display: {file: ("input (...) -> logged [...]", stack trace or "")}."""
    if not files:
        return {}
    paths = " ".join(f"{inputs_dir}/{f}" for f in files)
    out = env.exec_cmd(f"echo {paths} | ({_replay_cmd(test_class, test_method, classpath)}) 2>&1", timeout=300).stdout
    # JUnit's report at the end lists the failures in order: "1) method(Class)", then the trace
    report = re.split(r"^There (?:was|were) \d+ failures?:$", out, maxsplit=1, flags=re.MULTILINE)
    traces = [b.split("\n", 1)[1].strip() for b in re.split(r"^\d+\) ", report[1], flags=re.MULTILINE)[1:]
              if "\n" in b] if len(report) == 2 else []
    shown: dict[str, tuple[str, str]] = {}
    start = 0
    for m in _REPLAY_RESULT.finditer(out):
        trial = parse_jqf_output(out[start:m.start()], "")
        start = m.end()
        failed = m.group(2) not in ("SUCCESS", "INVALID")
        shown[m.group(1)] = ((trial.passing or ["input (not logged)"])[-1],
                             traces.pop(0) if failed and traces else "")
    return shown
