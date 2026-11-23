# Patch datasets

The patch dataset for the 77 benchmark bugs (`../bugs77.txt`), in Poracle's format.
`scripts/build_patch_datasets.py` writes it, `scripts/verify_patches.py` checks it, and
`scripts/run_experiment.py` evaluates tests on it.

| dataset | patches | bugs | labels | source |
|---|---|---|---|---|
| `apr/` | 458 | 77 | 132 Correct, 326 Incorrect | Poracle's artifact: every config in `configs/` whose tool is not `developer` |

## Format

```
<dataset>/
├── index.json          one record per patch
├── configs/<ID>.json   the Poracle config: ID, project, bug_id, tool, correctness, target
├── patches/<ID>        a unified diff, applied to the buggy checkout with `patch -p1`
└── verification.json   what scripts/verify_patches.py found for each patch
```

An `index.json` record has `id`, `dataset`, `bug` (e.g. `Math-81`), `tool`, `label`
(`Correct` / `Incorrect`), `target` and `patch_sha256`. `target` is Poracle's: a list of
`<source file>:<line>` in the patched version, the lines the differential fuzzer tries to reach.

Poracle applies a patch to the buggy checkout *after* the bug's delta (its build files, and for
Math-7, Math-33 and Math-87 a replaced main source file), so that is the tree every patch here
is made for and verified on.

## `apr/`

Configs and patch files are byte-for-byte copies (the host copy of Poracle's artifact and the
`poracle-reproduce` container hold the same files). Not included:

- the 78 developer patches (`"tool": "developer"`), which are not tool-generated. Lang-7 has no
  other patch, which is why the benchmark has 77 bugs and not Poracle's 78;
- Poracle's other patch sets (`duplicate_patches/`, `ext_configs/`, `configs-jaid/`).

The labels are Poracle's. Four patches that the original datasets call correct are `Incorrect`
here, as in Poracle's configs: Patch7, Patch26, Patch54 and Patch192 (Poracle's README,
"Evidence for mislabeled patches").

## Verification

`verify_patches.py` takes each patch through the steps Poracle and the experiment take, in the
container: buggy checkout, delta, `patch -p1`, `defects4j compile`, then every trigger test
Defects4J lists for the bug. `verification.json` has a row per patch:

| field | meaning |
|---|---|
| `applies`, `inexact` | `patch -p1` succeeded; the hunks it placed with an offset or fuzz |
| `compiles` | `defects4j compile` succeeded |
| `trigger_tests`, `plausible` | each trigger test's result on the patched version; all pass |
| `plausible_for_poracle` | the same for the trigger tests Poracle's own plausibility step runs (some deltas narrow the list); Poracle rejects a patch that fails one without fuzzing it |
| `fails_on_developer_fix` | trigger tests that fail on the developer-fixed version too in this container (checked for every bug with a failing patch); they do not count against `plausible` |

Results (2026-11-20, container `poracle-reproduce`):

| dataset | patches | apply | compile | pass every trigger test |
|---|---|---|---|---|
| `apr` | 458 | 458 | 458 | 433 |

- **apr.** Every patch applies and compiles; `patch` places 5 of them with fuzz (Math-32,
  Math-73 and three of Math-95). 25 patches fail a trigger test of their bug: 23 Incorrect and
  2 Correct ones (`481_PFL_SimFix_Patch_91_91` of Math-71, `510_PFL_FixMiner_Patch_2_2` of
  Math-35). That is how Poracle's dataset is, not a problem of applying them. For 18 of the 25
  (all Incorrect, 11 bugs) the failing test is one Poracle's plausibility step runs, so Poracle
  rejects them without fuzzing, whichever preservation test it is given; the experiment's
  report counts those separately (`implausible`).
- The 25 were verified twice with identical results. One other patch,
  `225_NFL_kPAR_Patch_1593_845` (Lang-53), failed its trigger test once while the container
  was heavily loaded and passed in two later runs; the file has the later result.
