"""JQF output -> grouped trials, on real jqf-random output (Math-35 fixtures).

Expected counts are derived from the raw text independently of the parser.
Run: python checks/03_jqf_grouping.py
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from preserve.jqf import parse_jqf_output  # noqa: E402

FIX = Path(__file__).parent / "fixtures"
CLS = "JQF_ElitisticListPopulationTest"


def trial_starts(raw: str) -> int:
    n, prev_sep = 0, False
    for line in raw.split("Time: ")[0].splitlines():
        line = line.lstrip(".")
        if line.startswith("in: "):
            if line[4:] == ";":
                prev_sep = True
                continue
            if not prev_sep:
                n += 1
            prev_sep = False
    return n


def live_failures(raw: str) -> int:
    return len(re.findall(r"^org\.junit\.ComparisonFailure", raw.split("Time: ")[0], re.M))


for name, n_args in [("single", 1), ("multi", 2)]:
    raw = (FIX / f"jqf_{name}.txt").read_text()
    gi = parse_jqf_output(raw, CLS)
    failing = sum(len(v) for v in gi.failing.values())
    passing = len(gi.passing)
    print(f"{name}: trials={gi.trials} discarded={gi.discarded} failing={failing} passing={passing}")
    assert gi.trials == trial_starts(raw)
    assert failing == live_failures(raw) and passing == 0
    assert gi.discarded + failing + passing == gi.trials
    assert gi.failing and not gi.passing and not gi.too_strong
    for key, inputs in gi.groups.items():
        assert key.startswith("org.junit.ComparisonFailure") and CLS in key.splitlines()[-1], key
        for s in inputs:
            assert s.count(",", 0, s.index(")")) == n_args - 1, s     # n_args values inside (...)

gi = parse_jqf_output((FIX / "jqf_multi.txt").read_text(), CLS)
print("\nshow_failing():\n" + gi.show_failing())

strong = parse_jqf_output((FIX / "jqf_too_strong.txt").read_text(), CLS)
print(f"\ntoo_strong: trials={strong.trials} discarded={strong.discarded} aborted={strong.too_strong}")
assert strong.too_strong and not strong.groups

print("\nJQF grouping checks passed")
