# Experiment main

Written 2026-11-23T11:47:55 from `experiments/main`.

## Test generation: 77 bugs

| generated test | bugs |
|---|---|
| valid | 48 |
| invalid | 15 |
| no bug report | 9 |
| no test | 5 |

LLM calls 1073, cost $74.44, tokens {'input': 2078, 'output': 816580, 'cache_write': 15872481, 'cache_read': 27808473}, time 6.1 h (of which the LLM 2.2 h). Per bug: report/generation.csv.

valid: every input inside buggyIf fails on the buggy version and passes on the developer fix. Only valid tests are used on patches unless the evaluation ran with --no-gate.

| bug | generated test | why |
|---|---|---|
| Chart-3 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-7 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-13 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-14 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-15 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-19 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-21 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-25 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Chart-26 | no bug report | Defects4J lists no bug report for this bug, so the agent has no input |
| Lang-44 | invalid | some inputs inside buggyIf fail on the developer fix |
| Lang-53 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-7 | invalid | some inputs inside buggyIf pass on the buggy version |
| Math-24 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-25 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-28 | no test | LLM call budget exhausted (50 calls) |
| Math-32 | no test | LLM call budget exhausted (50 calls) |
| Math-33 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-50 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-58 | no test | LLM call budget exhausted (after 53/50 LLM calls) |
| Math-61 | no test | The failures do not reproduce the bug: These failures do not reproduce the reported bug, and the result is the same as in every earlier round. The report is about the constructor PoissonDistributionIm |
| Math-71 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-81 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-82 | no test | LLM call budget exhausted (50 calls) |
| Math-84 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-87 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-88 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-90 | invalid | some inputs inside buggyIf fail on the developer fix |
| Math-99 | invalid | some inputs inside buggyIf fail on the developer fix |
| Time-14 | invalid | some inputs inside buggyIf fail on the developer fix |


## APR patches (labelled): 458 patches of 77 bugs

A test is right on a patch when it rejects an Incorrect patch (R: the test fails) and accepts a Correct one (A: the test passes).

### Baseline comparison, preservation tests only (valid generated tests)

The 259 patches (47 bugs) that both our preservation test and Poracle's test fuzzed and gave a verdict for. Left out: the patches that fail a trigger test of their bug (15). Poracle rejects those before any fuzzing, whichever preservation test it is given, so they say nothing about either test.

| tests | incorrect patches rejected | correct patches accepted | right | no verdict |
|---|---|---|---|---|
| ours: preserve | 92/170 | 76/89 | 168/259 (64.9%) | 0 |
| Poracle's test | 86/170 | 89/89 | 175/259 (67.6%) | 0 |

Both right 127, only ours 41, only Poracle's 48, neither 43.

### Repro + preserve (valid generated tests)

The 274 patches (48 bugs) our tests gave a verdict for. Repro + preserve rejects a patch when the repro test fails on it or the preservation test finds a difference. The preserve rows include the patches rejected before fuzzing for failing a trigger test (15).

| tests | incorrect patches rejected | correct patches accepted | right | no verdict |
|---|---|---|---|---|
| ours: repro | 72/185 | 88/89 | 160/274 (58.4%) | 0 |
| ours: preserve | 107/185 | 76/89 | 183/274 (66.8%) | 0 |
| ours: repro + preserve | 145/185 | 75/89 | 220/274 (80.3%) | 0 |
| Poracle's test | 101/185 | 89/89 | 190/274 (69.3%) | 0 |

### All patches of the dataset

Every patch; one whose bug has no usable generated test, or that was not run, has no verdict.

| tests | incorrect patches rejected | correct patches accepted | right | no verdict |
|---|---|---|---|---|
| ours: preserve | 107/185 | 76/89 | 183/274 (66.8%) | 184 |
| ours: repro | 72/185 | 88/89 | 160/274 (58.4%) | 184 |
| ours: repro + preserve | 145/185 | 75/89 | 220/274 (80.3%) | 184 |
| Poracle's test | 181/326 | 130/132 | 311/458 (67.9%) | 0 |
| Poracle's test, its published runs | 214/214 | 69/71 | 283/285 (99.3%) | 173 |

### Per bug

| bug | generated test | patches (incorrect / correct) | ours: preserve right | ours: repro + preserve right | Poracle's right |
|---|---|---|---|---|---|
| Chart-1 | valid | 2 (2 / 0) | 0/2 | 2/2 | 0/2 |
| Chart-3 | no bug report | 1 (1 / 0) | - | - | 0/1 |
| Chart-5 | valid | 3 (3 / 0) | 3/3 | 3/3 | 3/3 |
| Chart-7 | no bug report | 1 (1 / 0) | - | - | 0/1 |
| Chart-9 | valid | 1 (1 / 0) | 0/1 | 1/1 | 0/1 |
| Chart-13 | no bug report | 4 (4 / 0) | - | - | 4/4 |
| Chart-14 | no bug report | 1 (0 / 1) | - | - | 1/1 |
| Chart-15 | no bug report | 2 (2 / 0) | - | - | 2/2 |
| Chart-17 | valid | 1 (1 / 0) | 0/1 | 0/1 | 0/1 |
| Chart-19 | no bug report | 1 (0 / 1) | - | - | 1/1 |
| Chart-21 | no bug report | 2 (2 / 0) | - | - | 2/2 |
| Chart-25 | no bug report | 4 (4 / 0) | - | - | 4/4 |
| Chart-26 | no bug report | 3 (3 / 0) | - | - | 2/3 |
| Lang-24 | valid | 6 (1 / 5) | 5/6 | 5/6 | 5/6 |
| Lang-35 | valid | 1 (1 / 0) | 1/1 | 1/1 | 1/1 |
| Lang-39 | valid | 8 (5 / 3) | 4/8 | 4/8 | 5/8 |
| Lang-44 | invalid | 5 (4 / 1) | - | - | 4/5 |
| Lang-46 | valid | 6 (1 / 5) | 6/6 | 6/6 | 6/6 |
| Lang-51 | valid | 2 (2 / 0) | 2/2 | 2/2 | 2/2 |
| Lang-53 | invalid | 4 (4 / 0) | - | - | 1/4 |
| Lang-55 | valid | 4 (2 / 2) | 2/4 | 2/4 | 2/4 |
| Lang-57 | valid | 1 (0 / 1) | 1/1 | 1/1 | 1/1 |
| Lang-58 | valid | 14 (12 / 2) | 2/14 | 5/14 | 11/14 |
| Math-2 | valid | 9 (9 / 0) | 0/9 | 9/9 | 1/9 |
| Math-3 | valid | 3 (0 / 3) | 3/3 | 3/3 | 3/3 |
| Math-4 | valid | 13 (10 / 3) | 10/13 | 10/13 | 10/13 |
| Math-5 | valid | 9 (0 / 9) | 9/9 | 9/9 | 9/9 |
| Math-7 | invalid | 2 (2 / 0) | - | - | 0/2 |
| Math-8 | valid | 8 (8 / 0) | 8/8 | 8/8 | 6/8 |
| Math-22 | valid | 4 (3 / 1) | 2/4 | 2/4 | 2/4 |
| Math-24 | invalid | 1 (1 / 0) | - | - | 1/1 |
| Math-25 | invalid | 2 (0 / 2) | - | - | 2/2 |
| Math-28 | no test | 14 (14 / 0) | - | - | 14/14 |
| Math-32 | no test | 5 (5 / 0) | - | - | 0/5 |
| Math-33 | invalid | 8 (5 / 3) | - | - | 3/8 |
| Math-34 | valid | 2 (0 / 2) | 2/2 | 2/2 | 2/2 |
| Math-35 | valid | 7 (1 / 6) | 6/7 | 5/7 | 7/7 |
| Math-39 | valid | 1 (1 / 0) | 1/1 | 1/1 | 0/1 |
| Math-40 | valid | 7 (7 / 0) | 7/7 | 7/7 | 7/7 |
| Math-41 | valid | 5 (3 / 2) | 0/5 | 3/5 | 2/5 |
| Math-42 | valid | 4 (4 / 0) | 4/4 | 4/4 | 3/4 |
| Math-44 | valid | 1 (1 / 0) | 0/1 | 1/1 | 0/1 |
| Math-49 | valid | 6 (6 / 0) | 4/6 | 5/6 | 4/6 |
| Math-50 | invalid | 22 (10 / 12) | - | - | 13/22 |
| Math-53 | valid | 6 (3 / 3) | 4/6 | 4/6 | 4/6 |
| Math-57 | valid | 8 (3 / 5) | 5/7 | 7/7 | 7/8 |
| Math-58 | no test | 11 (5 / 6) | - | - | 8/11 |
| Math-61 | no test | 2 (0 / 2) | - | - | 2/2 |
| Math-69 | valid | 3 (3 / 0) | 1/3 | 3/3 | 3/3 |
| Math-70 | valid | 10 (0 / 10) | 10/10 | 10/10 | 10/10 |
| Math-71 | invalid | 4 (2 / 2) | - | - | 2/4 |
| Math-73 | valid | 9 (9 / 0) | 0/9 | 6/9 | 9/9 |
| Math-78 | valid | 4 (4 / 0) | 0/4 | 4/4 | 4/4 |
| Math-80 | valid | 18 (18 / 0) | 18/18 | 18/18 | 0/18 |
| Math-81 | invalid | 27 (27 / 0) | - | - | 26/27 |
| Math-82 | no test | 19 (12 / 7) | - | - | 7/19 |
| Math-84 | invalid | 14 (14 / 0) | - | - | 14/14 |
| Math-85 | valid | 23 (16 / 7) | 16/23 | 16/23 | 10/23 |
| Math-87 | invalid | 3 (3 / 0) | - | - | 0/3 |
| Math-88 | invalid | 13 (13 / 0) | - | - | 1/13 |
| Math-89 | valid | 6 (0 / 6) | 6/6 | 6/6 | 6/6 |
| Math-90 | invalid | 3 (0 / 3) | - | - | 3/3 |
| Math-93 | valid | 3 (2 / 1) | 1/3 | 1/3 | 1/3 |
| Math-95 | valid | 14 (14 / 0) | 14/14 | 14/14 | 14/14 |
| Math-97 | valid | 6 (6 / 0) | 4/6 | 4/6 | 1/6 |
| Math-99 | invalid | 3 (0 / 3) | - | - | 3/3 |
| Math-104 | valid | 2 (2 / 0) | 0/2 | 2/2 | 0/2 |
| Math-105 | valid | 4 (4 / 0) | 4/4 | 4/4 | 4/4 |
| Time-4 | valid | 5 (5 / 0) | 2/5 | 3/5 | 5/5 |
| Time-7 | valid | 6 (1 / 5) | 5/6 | 5/6 | 5/6 |
| Time-11 | valid | 6 (6 / 0) | 2/6 | 3/6 | 5/6 |
| Time-12 | valid | 1 (1 / 0) | 1/1 | 1/1 | 1/1 |
| Time-14 | invalid | 2 (2 / 0) | - | - | 1/2 |
| Time-15 | valid | 3 (0 / 3) | 3/3 | 3/3 | 3/3 |
| Time-16 | valid | 1 (1 / 0) | 0/1 | 0/1 | 0/1 |
| Time-18 | valid | 2 (2 / 0) | 0/2 | 0/2 | 1/2 |
| Time-19 | valid | 7 (2 / 5) | 5/7 | 5/7 | 5/7 |

Runs done: {'ours': 275, 'poracle': 458}; machine time 17.0 h (ours), 18.5 h (Poracle's). Per patch: report/apr_patches.csv.

Poracle's test ran in: poracle-original (original fuzzer) 458. Ours ran in poracle-reproduce (our fuzzer changes).
