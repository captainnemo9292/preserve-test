# preserve-testing

LLM-generated **preservation tests** for Poracle,
the differential fuzzer that validates program-repair patches.

Poracle needs a hand-written JQF test per bug. For each test, a *preservation condition*
says which inputs the patch must leave unchanged. This project generates those tests from the
bug report alone:

```
step 1  generate a test for a bug                                   scripts/generate.py
        bug report ──► agent (AutoCodeSherpa's PBT agent, Java/JQF, claude CLI) ──► repro test
                       refined by fuzzing the BUGGY version only: every input in buggyIf must
                       fail an assertion
        repro test ──► derivation ──► preservation test (preserveIf = !buggyIf)
        validity   ──► the repro test is fuzzed on the buggy version and on the developer fix:
                       valid only if every input fails on buggy and every input passes on the fix

step 2  check patches with a valid test                             scripts/run_experiment.py evaluate
        repro      ──► on the patched version: must pass (same inputs as buggy + fresh fuzzing)
        preserve   ──► differential fuzzing, buggy vs patched: must show no difference
```

The developer fix is never shown to the agent. It is used only to decide whether a finished
test is valid, and by Poracle's own validation step.

`scripts/run_experiment.py` runs both steps as one experiment on the 77 benchmark bugs and
Poracle's APR patch dataset, with Poracle's own tests as the baseline: see [§8](#8-the-experiment).

---

## 1. The test format

The agent writes a single `@Fuzz` method in the class Poracle expects for the bug
(`JQF_<FailingTestClass>`). The method is named `testBugReproduction` (`config.REPRO_METHOD`),
not after the failing test, so its name gives no hint of the trigger test.

```java
@RunWith(JQF.class)
public class JQF_StringUtilsTest {

    public static class SearchGen extends Generator<String[]> { ... }   // optional custom generators

    @Fuzz
    public void testBugReproduction(@From(TextGen.class) String text,
                                    @From(SearchGen.class) String[] searchList,
                                    @InRange(minInt = 0, maxInt = 10) int n) {
        boolean validIf = <the inputs the API accepts>;              // VALID DOMAIN
        boolean buggyIf = <exactly the inputs that trigger the bug>; // BUG DOMAIN

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        <call the code under test, keep results in locals>

        Log.logOutIf(true, () -> new Object[]{ <returned values, exception class, resulting state> });
        <local variables only the assertions need, e.g. the expected value>
        <assertions that fail on the buggy code and pass once it is fixed>
    }
}
```

What the prompt asks for (`SYMBOLIZE` in `src/preserve/prompts.py`):

- **Parameters = API arguments.** There is one parameter per argument of the API under test,
  including constructor and set-up calls. Primitive parameters use `@InRange`. Strings, arrays,
  collections and objects use a `@From` generator that builds the whole value, covers the valid
  domain (null, empty and boundary values included) and produces bug-triggering inputs 30–50%
  of the time.
- **Range bounds.** The prompt states how the container's fuzzer reads `@InRange`, measured
  with a probe test: `@InRange(minInt = a, maxInt = b)` produces only `a` to `b − 1`, and the
  same holds for `short`, `byte` and `char`; a `long` range includes both bounds; a `boolean`
  takes both values. Before this was in the prompt, Chart-1's test wrote its 0/1 switches as
  `@InRange(minInt = 0, maxInt = 1)`, so they were always 0 and half of its input space was
  never generated.
- **Values for the assertions go after the log line.** Whatever only the assertions need
  (e.g. the expected value) is computed after `Log.logOutIf`, because the preservation method
  keeps only the code before it. Computed before it, such a value also runs on the preserved
  inputs and can throw there (Lang-58's test did, for every input with a leading `-`).
- **Domains.** The ranges and `validIf` together cover every valid input. `buggyIf` is true
  for all of the bug-triggering inputs and for nothing else.
- **Log the observable behavior.** The logged values are what the code returned, the class of
  any exception it threw, and the resulting object state, not only what the assertions check.
  Every logged value must be deterministic.

`preserve/derive.py` checks this structure before every execution (`format_problems`). It
reports a violation to the agent as a compile error:

- the method has `@Fuzz` and at least one parameter;
- each parameter is annotated with `@InRange`/`@From`, or is a `boolean`;
- the first statements are `validIf`, `buggyIf`, `assumeTrue(validIf)` and `assumeTrue(buggyIf)`;
- there is exactly one `Log.logOutIf(true, …)`, followed only by local variable declarations
  and assertions, with at least one assertion;
- `buggyIf` references a parameter (no vacuous `buggyIf = true`);
- the class is annotated with `@RunWith(JQF.class)`.

### Derivation of the preservation method

`derive(src, repro_name, preserve_name)` copies the repro method under the failing test's name
(the one Poracle runs), then changes the copy:

- adds `boolean preserveIf = !buggyIf;` and replaces `assumeTrue(buggyIf)` with `assumeTrue(preserveIf)`;
- turns `Log.logOutIf(true, …)` into `Log.logOutIf(preserveIf, …)`;
- drops everything after the log line: the assertions and the declarations they use;
- wraps the body in `try { … } catch (Exception e) { Log.logOutIf(preserveIf, e.toString()) }`,
  rethrowing JQF's `TimeoutException`. Exceptions thus become compared outputs, not crashes.

The resulting file holds both methods. Poracle fuzzes the preservation method on the buggy
and patched versions, and a patch is rejected (`R`) when some input in `validIf && !buggyIf`
logs different output.

---

## 2. Code structure

```
src/preserve/
├── config.py       paths, container name, defaults (PRESERVE_RESULTS_DIR overrides results/)
├── bugs.py         the bug list (Poracle benchmark ∩ latest Defects4J) and report text
├── container.py    DockerEnv (docker exec) and Checkout (Poracle's checkout/delta/compile steps)
├── llm.py          ClaudeCLI (`claude -p`, one session per bug) and VerboseLog
├── guard.py        the hook that keeps the model's Read/Grep/Glob inside the snapshot
├── jqf.py          fuzz a JQF test (Zest) and replay saved inputs; outcomes as a JqfRun
├── prompts.py      every prompt and feedback message
├── agent.py        ReproAgent, the AutoCodeSherpa graph: writes the repro test
├── derive.py       the format check and the derivation of the preservation method
├── repro.py        the repro method on a version: paired replay + fresh fuzzing; validate()
├── pipeline.py     generate(bug): checkout → agent → derivation → Poracle delta → validation
├── patchsets.py    the patch datasets under data/patches/
└── evaluate.py     one patch: our repro + preservation test, and Poracle's own test

scripts/            one per stage, in order
├── fetch_reports.py        once: download bug reports → data/reports/
├── install_fuzzer.py       once: condition-aware fuzzer + our Log fix into the container
├── build_patch_datasets.py once: the patch datasets → data/patches/
├── verify_patches.py       once: every patch applies, compiles and passes the trigger tests
├── run_experiment.py       the experiment: generate → evaluate → report (§8)
├── generate.py             step 1 alone, for one or many bugs (outside an experiment)
└── validate.py             re-run the validity check for existing tests

data/defects4j/     latest Defects4J active-bugs.csv files (report URLs)
data/reports/       the fetched bug reports, one JSON per bug (committed: the agent's inputs)
data/patches/       the patch dataset: apr/ (Poracle's tool-generated patches; see its README)
data/bugs77.txt     the benchmark of the experiment: the bugs with a patch in data/patches/apr
data/bugs15.txt     an example bug list
checks/             small self-checks (see §4)
experiments/main/   the experiment's results (§8), without the workspace snapshots and paired-input archives
```

The agent and the Poracle steps are reduced versions of AutoCodeSherpa's and Poracle's code.

### Step 1: the agent (`agent.py`)

`ReproAgent` is AutoCodeSherpa's PBT-generation graph, with JQF in place of Hypothesis:

```
generalize ─► symbolize ─► execute ─► check_inputs ─► validate ─► success
                  ▲           │            │             │
                  │           │            │             └─ some inputs pass ─► filter_passing ─► execute
                  └───────────┴────────────┴─ feedback: bad structure / compile error / no input
                                              checked / test passed / wrong exception /
                                              not relevant / validation issue
```

- **Nodes.**
  - `generalize` turns the report into a summary and concrete examples.
  - `symbolize` writes the test.
  - `execute` checks the structure, compiles, fuzzes the buggy version and groups the inputs by outcome.
  - `check_inputs` asks whether the failures are assertion failures that reproduce the report.
  - `validate` checks for mocking, hard-coded failures and improper equality checks.
  - `filter_passing` narrows `buggyIf` when some inputs pass.

  Every route back to `symbolize` queues a feedback message (`prompts.py`) that goes out with
  the next prompt. The run fails when the budget of 50 LLM calls is spent.
- **LLM** (`llm.py`). One resumed `claude -p` session per bug, so each call sends only the new
  prompt. Answers are structured (`--json-schema`). `symbolize` and `filter_passing` also get
  read-only `Read`/`Grep`/`Glob` tools on a host snapshot of the buggy checkout
  (`workspace/<bug>/source`); such a call counts all of its turns against the budget.
- **What the model can read.** Only the snapshot, and not the bug's tests:
  - `Checkout.snapshot_to` deletes Defects4J's *relevant tests* (`tests.relevant`: the test
    classes that load a class the developer fix modifies, the trigger tests included) from the
    snapshot. The checkout in the container keeps them. The hidden classes are logged and listed
    in `result.json` (`hidden_tests`).
  - `guard.py`, a `PreToolUse` hook passed to every `claude -p` call with tools, blocks a
    `Read`/`Grep`/`Glob` whose path resolves outside the snapshot (symlinks followed), and Glob
    patterns that are absolute or contain `..`. Without it the model could read any host file,
    e.g. a later bug's snapshot of the same project, which holds this bug's trigger test and fix.
- **Execution** (`jqf.py`, `fuzz`), on the buggy version only:
  1. write the test into the checkout in the container and compile it with `ant compile.tests`;
  2. fuzz it with JQF's coverage-guided fuzzer (Zest, `jqf-zest`) for `--fuzz-seconds`, with the
     project classes *first* on the classpath, saving every input tried;
  3. replay a sample of 5,000 of those inputs with JQF's `ReproDriver` to count them by
     outcome (rejected by `assumeTrue`, passed, or failed with which exception), and replay a
     few per outcome to show the model their arguments, logged values and stack trace.

  Only a JUnit assertion failure counts as reproducing the bug.
- **Container** (`container.py`). `Checkout` prepares the buggy version, the developer fix or a
  patched version the way Poracle does: `defects4j checkout`, the bug's build-file delta (Poracle's own
  `JQF_*.java` tests are never copied), compile, trigger tests, classpath.

### The repro check and validity (`repro.py`)

The repro method only looks at inputs inside `buggyIf`, and its assertions say what the fixed
code must do there. So every such input must fail on the buggy version and pass on a version
that fixes the bug. A version is checked on two sets of inputs:

- **Paired.** The buggy version is fuzzed once per bug (60 s), and up to 20,000 of its inputs
  inside `buggyIf` are kept. A saved input is the random bytes its arguments were generated
  from, so replaying it on another version runs the test on exactly the same arguments.
- **Fresh.** The other version is fuzzed itself (60 s), which can reach code that only exists
  there.

A generated test is **valid** when all paired inputs fail on the buggy version, and all
inputs, paired and fresh, pass on the developer fix. `generate.py` checks this when the agent
finishes; `scripts/validate.py` re-runs it. The verdict is in `result.json` (`valid`,
`validation`). Step 2 uses only valid tests (`run_experiment.py evaluate --no-gate` includes the rest).

### Step 2: patches (`evaluate.py`, run by `run_experiment.py evaluate`)

For each bug with a valid test, every patch of that bug in `data/patches/apr` gets two checks
in the container:

| check | test method | fuzzer | versions | outcome |
|---|---|---|---|---|
| **repro** | repro method | JQF Zest + replay | patched alone, on the paired inputs and fresh ones | `fail` if some input inside `buggyIf` fails the assertions, `pass`, or `unknown` (doesn't compile, no input checked) |
| **preserve** | preservation method | Poracle, differential | buggy vs patched, same input on both | `R` if some input outside `buggyIf` logs different output, else `A` |

The combined verdict is `R` if repro fails or preserve rejects, `A` if preserve accepts (also
when repro is `unknown`), and `?` otherwise.

Per patch (`evaluate_ours`):

1. **Patched checkout**, built as Poracle builds it: buggy checkout, build delta,
   `patch -p1 < patches/<Patch>`, compile.
2. **repro**: our test file is written there and compiled; the bug's paired inputs are
   replayed, then the patched version is fuzzed for `--repro-seconds`.
3. **preserve**: the unmodified command
   `poracle configs/<Patch>.json --deltas-dir <ours> --duration 3m --skip-tests 2 3 …`
   (our delta only has a method for Poracle's `test1`).

Poracle's own test runs the same command with Poracle's deltas, in the `poracle-original`
container (`evaluate_poracle`). If a step after fuzzing fails (e.g. the test doesn't compile on
the fixed version), `recover_judge` recovers the verdict from the fuzzing output with Poracle's
rule. §8 describes the experiment around this: repetitions, resuming, outputs and the report.

### The fuzzer in the container (`scripts/install_fuzzer.py`)

The container is the original `poracle-reproduce`, with two sets of changes installed:

- **The condition-aware differential fuzzer** from `rlaaudrb/poracle-cond-aware:v1`. Its ten
  files are copied over unchanged: `PoracleGuidance`, `ZestCLI2`, `InRangeFactory`,
  `FuzzStatement`, `Log`, the new `CriticalBiasedInRange` and `CriticalValuePool`, and
  Poracle's `main.py`, `poracle.py` and the new `extract_critical_values.py`. The bias itself
  (`poracle --cond-aware`) is never turned on by the experiment: it is drawn separately for the
  buggy and the patched run, so the two can receive different arguments for the same input,
  and a correct patch is then rejected (seen on Chart-5's developer fix). Without the flag the
  fuzzer behaves as the original.
- **Our `Log` fix**, on top of that `Log.java`. `Log.logIn/logOut` accepted only strings, boxed
  primitives and `Object[]`; a `null`, a primitive array or any other object threw. In `logIn`
  (called on every fuzzed argument) that stops the whole fuzzing run, which breaks tests whose
  `@From` generators produce arrays, lists or nulls. Now, in `logIn`, `null` becomes `"null"`;
  in both, primitive arrays are logged element by element and other objects use
  `String.valueOf`. A `null` passed directly to `logOut` still throws; in a preservation method
  that exception is caught and its text is logged instead.

JQF is then rebuilt (`mvn -pl fuzz -am install`) and the `Log` classes are synced into the
other jar copies. The originals are kept in `/poracle/modules/JQF/.preserve-backup/src`, and
`--restore` puts the original Poracle back.

---

## 3. Setup

```bash
# Python 3.12 env; the only dependencies are langgraph and pydantic
conda create -n preserve python=3.12 && conda activate preserve
pip install -e .

# once, in the Poracle container: our fuzzer changes (§2)
docker pull rlaaudrb/poracle-cond-aware:v1
python scripts/install_fuzzer.py
```

The bug reports are committed in `data/reports/`. `scripts/fetch_reports.py` downloads them
again, which can change the agent's inputs, so only run it to refresh them on purpose.

Requirements:

- **Poracle's artifact** on the host: its `deltas/` (the bugs' build files), `configs/` and
  the benchmark bug list are read from there. Default `/home/byoungwoo/patch_validation/poracle`;
  set `PRESERVE_PORACLE_HOST` to its location.
- **The `poracle-reproduce` container** (Poracle's image, see Poracle's README), running, with
  our fuzzer changes installed. Ours ran pinned to 4 CPUs, which allows about 3 parallel jobs.
- **A second container, `poracle-original`**, for the experiment's Poracle baseline (§8): made
  from the original image without our fuzzer changes, so Poracle's own tests run on Poracle's
  original fuzzer while ours run in `poracle-reproduce`. Pin it to free cores:
  `docker run -d --name poracle-original --cpuset-cpus <cores> poracle100/poracle-reproduce:latest /root/start.sh`
  (`PORACLE_CONTAINER` in `config.py`, `$PRESERVE_PORACLE_CONTAINER`, or `--poracle-container`).
- **The `claude` CLI**, installed and logged in.

---

## 4. Running

`scripts/run_experiment.py` (§8) is the way to run the experiment. The commands below run single
stages outside an experiment, into `$PRESERVE_RESULTS_DIR` (default `results/`).

**Step 1: generate and validate tests**

```bash
python scripts/generate.py Math-35 -v                 # one bug; -v also prints verbose.log live
python scripts/generate.py -f data/bugs15.txt -j 2    # a list of bugs (one name per line)
python scripts/generate.py --all -j 2 --resume        # all 69 usable bugs, skipping finished ones
python scripts/validate.py Lang-44                    # only re-run the validity check
```

Options: `--model` (a Claude CLI model name, default `sonnet`), `--fuzz-seconds` (fuzzing per
execution while the agent refines the test, default 15) and `--max-llm-calls` (default 50).

**Self-checks**

```bash
python checks/01_cli.py                 # Claude CLI: structured output, tools, session (haiku, a few cents)
python checks/02_checkout.py Math-35    # Poracle's buggy-version steps in the container
python checks/03_jqf_grouping.py        # JQF output -> grouped trials (offline fixtures)
python checks/04_derive.py [--compile]  # format check + derivation (fixture repro_math35.java)
python checks/05_confinement.py         # Read/Grep/Glob can't leave the snapshot (offline + haiku, a few cents)
python checks/06_hidden_tests.py Math-35 [...]  # the snapshot has no relevant tests; the container keeps them
```

---

## 5. Outputs

`$PRESERVE_RESULTS_DIR` (default `results/`):

| path | content |
|---|---|
| `<bug>/result.json` | `success`, `valid`, `validation` (the repro check on buggy and on the fix), `failure_reason`, `llm_call_count`, `cost_usd`, `tokens`, `seconds`, `failing_test`, `jqf_class`, `hidden_tests`, `repro_method`, `preserve_method`, `derived`, `derived_compiles`, `poracle_delta` |
| `<bug>/repro.java` | the final bug-reproducing test |
| `<bug>/JQF_<Class>.java` | the final test file: repro method **and** derived preservation method |
| `<bug>/verbose.log` | the full readable run: prompts, model text, tool calls and results, each test version, compile and fuzzing results, the derivation and the validation |
| `deltas/<Project>/<Project>_bug<N>/` | a Poracle `deltas` root: the bug's build files from Poracle plus **our** test |
| `<bug>/paired.json` | the bug's paired inputs: where they are in the container, and each one's outcome on the buggy version |

`workspace/<bug>/source` holds the host snapshot of the buggy checkout that the agent reads,
without the relevant tests. Snapshots taken before this was added still contain them; generating
the bug again replaces the snapshot. Inside
the container, checkouts live under `/tmp/preserve-testing/<bug>/` (`<Project><N>b` buggy,
`<Project><N>f` fixed, `<Project><N>p-<Patch>` patched, `paired-inputs/`).

---

## 6. Data

- **Bugs.** Poracle's 78 benchmark bugs, restricted to the **69** whose report is listed in
  the latest Defects4J (Math 45, Lang 11, Time 9, Chart 4). Bug IDs match between the
  container's Defects4J and the latest one: modified classes and trigger tests are identical.
- **Reports.** Fetched from Apache JIRA, SourceForge and GitHub using the URLs in
  `data/defects4j/` (Defects4J master 8c16da82). Time-12's report has a title only.
- **Patches.** Poracle's own data: `configs/*.json` (patch ID, tool, label `Correct`/`Incorrect`)
  and the patch files, copied by `build_patch_datasets.py`. The experiment uses the
  copy in `data/patches/apr/` instead (Poracle's 458 tool-generated patches of 77 bugs).
  `data/patches/README.md` describes the format and the verification.

---

## 7. Known limitations

- **`buggyIf` too narrow** shows only on patches: the fix then changes behavior inside the
  preserved domain, so preserve rejects correct patches. Validity can't catch it, because every
  input that *is* inside `buggyIf` still fails on buggy and passes on the fix. A too-wide
  `buggyIf` does make a test invalid (some input fails on the fix, or passes on buggy).
- **Domain and logging quality** depend on the model. A `validIf` narrower than what the API
  accepts, or a log that records only a success flag, hides patch differences. A preserved
  domain (`validIf && !buggyIf`) that is nearly empty makes the test vacuous.
- **Nondeterministic logged values** (e.g. sampling from an unseeded RNG) make every patch
  differ. Nothing checks for this yet; a buggy-vs-buggy run of the preservation test would.
- **Fuzzing is a sample.** "Every input" means every input tried: up to 20,000 paired inputs
  and 60 seconds of fresh fuzzing per version.
- **Only the first trigger test.** The target is Poracle's `test1`.
- **Numeric comparison.** Poracle compares a single logged number as a double, so NaN, `long`s
  above 2^53 and number-like strings can hide differences.

---

## 8. The experiment

`scripts/run_experiment.py` runs the whole comparison and keeps everything it produces.

**Benchmark.** The 77 Defects4J bugs that have a tool-generated patch in Poracle's dataset
(`data/bugs77.txt`; Poracle's 78th bug, Lang-7, has only the developer patch). Nine of them,
all Chart, have no bug report in Defects4J, so the agent has no input and they get no test
(`no bug report`).

**Patch dataset** (`data/patches/`, built by `build_patch_datasets.py`, checked by
`verify_patches.py`):

| dataset | patches | labels |
|---|---|---|
| `apr` | Poracle's 458 tool-generated patches of the 77 bugs | 132 Correct, 326 Incorrect |

**Stages.**

| stage | what it does |
|---|---|
| `generate` | per bug: the agent writes the repro test, the preservation test is derived, and the test is validated on the buggy version and the developer fix |
| `evaluate` | per patch, two suites: **ours** (repro on the patched version, then preserve with Poracle's differential fuzzer with our changes, in `poracle-reproduce`) and **poracle** (Poracle's own hand-written test, same duration and seed, on Poracle's original fuzzer, in `poracle-original`) |
| `report` | tables and statistics from what has been generated and evaluated so far |

**What is compared.**

- *Baseline comparison: preservation test against preservation test.* Our derived preservation
  test and Poracle's test both run through the same command (`poracle configs/<Patch>.json
  --duration D --deltas-dir <ours or Poracle's>`). Ours runs on our fuzzer (`poracle-reproduce`,
  §2: our tests need the Log fix); Poracle's runs on Poracle's original fuzzer
  (`poracle-original`, `--poracle-container`), i.e. Poracle as published. The original has no
  `--base-seed` (it always seeds with 0), so the poracle suite runs one repetition. Each result
  records its container; a Poracle result made in another container is moved to
  `<patch>/superseded/<container>/` and run again. Poracle's test is run again rather than
  read from its published results because the published ones cover only 285 of the 458 APR
  patches; the published verdicts are kept alongside
  (`poracle_stored`).
- *Repro + preserve*, reported separately: a patch is rejected when our repro test fails on it
  or our preservation test finds a difference.
- *`apr`*: a test is right on a patch when it rejects (R, the test fails) an Incorrect patch and
  accepts (A, the test passes) a Correct one.
- Poracle runs the bug's trigger tests on a patch first and rejects it without fuzzing if one
  fails. Such a rejection is the same whichever preservation test is used, so those patches are
  left out of the baseline comparison and counted separately (`implausible`).
- Our delta has a preservation method for the bug's first trigger test only, so for our suite
  Poracle's other test units are skipped (`--skip-tests`). Poracle's own suite runs all of its
  generalized tests for the bug.

**Running.**

```bash
python scripts/build_patch_datasets.py && python scripts/verify_patches.py   # once; already in data/patches/
python scripts/run_experiment.py all                        # everything, into experiments/main
python scripts/run_experiment.py generate -j 2              # or stage by stage
python scripts/run_experiment.py evaluate --duration 3m -j 3
python scripts/run_experiment.py evaluate --suites poracle --reps 3     # only the baseline, 3 repetitions
python scripts/run_experiment.py report
python scripts/run_experiment.py all --out experiments/smoke --bugs Math-35 --patches Patch199
```

Every stage resumes: a bug, or a (patch, suite, repetition), that has a result is skipped unless
`--redo` is given; a bug whose generation crashed (e.g. the CLI hit a usage limit) is tried
again, and `--retry-unknown` runs again the evaluations that ended without an A / R verdict. `--out` names the experiment directory; each experiment has its own directory in the
container (`/tmp/preserve-experiment/<name>-<hash>`), so experiments do not disturb each other
or the other scripts. When Docker refuses a command because the container is paused (as it is
for the length of a `docker commit`) or restarting, the command waits and is tried again for up
to half an hour, instead of failing at once and being taken for a result. `--reps N` repeats the evaluation with Poracle's `--base-seed 0..N-1`; the
report takes the majority verdict (a tie counts as R). `--no-gate` also uses generated tests
that are not valid, and the report then shows both scopes.

With the defaults (`--duration 3m`, 60 s of repro fuzzing) a patch takes roughly 10 minutes for
both suites (about 6 for ours, 4 for Poracle's), so the 458 patches take about a day at `-j 3`. In the smoke runs (Lang-58, Chart-1, model `sonnet`) generating a test took about
2 minutes and $0.5 to $1 per bug, plus about 5 minutes for the validity check.

**Outputs** (`experiments/<name>/`):

| path | content |
|---|---|
| `experiment.json`, `experiment.log` | the settings, commit and versions of every invocation; everything printed, with times |
| `generation/<bug>/result.json` | as in §5, plus `timing` (seconds per phase: checkout, agent, derive, validation), `total_seconds`, `llm_seconds`, `llm_calls` (per CLI call: step, seconds, turns, tokens, cost), `test_versions`, `no_report` / `crashed` |
| `generation/<bug>/trace.jsonl` | the agent's full trace, one JSON object per line: `llm_call` (the prompt), `llm_event` (every event the CLI streamed: the model's thinking and text, each tool call and its result), `llm_result` (usage, cost, structured answer), `execution` (each test version: structure problems, compile errors, or what the fuzzing run did), `node` (each graph node and what it decided), `phase` |
| `generation/<bug>/verbose.log` | the same run, readable |
| | The model's reasoning in these is its text, its tool use and the `reasoning` of its structured answers. Its *thinking* blocks are recorded as the CLI delivers them, which with CLI 2.1.329 and `sonnet` is without their text (only a signature), also when streamed. |
| `generation/<bug>/attempts/NNN.java` | every version of the test the agent wrote |
| `generation/<bug>/repro.java`, `JQF_<Class>.java` | the final repro test; the final file with the derived preservation method |
| `generation/<bug>/paired.json`, `paired-inputs.tar.gz` | the buggy run's inputs inside `buggyIf` and their outcomes; restored into the container when it has lost them. The archives are not in git (51 MB): without one, the buggy version is fuzzed again, so a re-run replays new inputs, not the original ones |
| `generation/deltas/` | our tests as a Poracle deltas directory |
| `workspace/<bug>/source` | the snapshot the agent could read |
| `evaluation/<dataset>/rep<k>/<patch>/ours.json` | repro (paired replay and fresh fuzzing, failing examples), preserve (verdict, Poracle's consistency check, the witness of a difference: input and the buggy, patched and fixed outputs, time per step), the combined verdict, seconds |
| `evaluation/<dataset>/rep<k>/<patch>/poracle.json` | the same preserve record for Poracle's own test |
| `evaluation/<dataset>/rep<k>/<patch>/*.poracle.log`, `*.statistics.json` | Poracle's log and statistics of each run; `<suite>.error.json` if a run crashed (it is tried again) |
| `evaluation/apr/poracle_stored.json` | Poracle's published verdicts (majority over its repeated runs) |
| `report/summary.md`, `stats.json` | the tables; the same numbers as JSON |
| `report/generation.csv` | per bug: status, LLM calls, cost, tokens, seconds per phase, why not valid |
| `report/<dataset>_patches.csv` | per patch: label, every verdict and its per-repetition counts, `implausible`, seconds |
