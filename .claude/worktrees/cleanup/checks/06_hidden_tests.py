"""The agent's snapshot of the buggy version has none of Defects4J's relevant tests (they contain
the trigger tests), but keeps the other tests; the checkout in the container keeps them all.

Run: python checks/06_hidden_tests.py [Math-35 ...]   (reuses a checkout already in the container)
"""

import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from preserve.bugs import Bug  # noqa: E402
from preserve.container import Checkout  # noqa: E402

for name in sys.argv[1:] or ["Math-35"]:
    c = Checkout(Bug.parse(name))
    if not c.env.exists(c.dir):
        c.prepare()
    relevant = sorted({t.split("$")[0] for t in c.relevant_tests()})
    trigger_classes = {t.split("::")[0] for t in c.trigger_tests()}
    assert trigger_classes <= set(relevant), f"{name}: a trigger test is not among the relevant tests"

    snap = c.snapshot_to(Path(tempfile.mkdtemp(prefix="hidden_check_")) / "source")
    tests_root = snap / c._tests_src_dir()
    left = [cls for cls in relevant if (tests_root / (cls.replace(".", "/") + ".java")).exists()]
    remaining = list(tests_root.rglob("*.java"))
    in_container = [cls for cls in relevant
                    if not c.env.exists(f"{c.dir}/{c._tests_src_dir()}/{cls.replace('.', '/')}.java")]
    leftovers = [p.name for p in snap.iterdir() if p.name in (".relevant_tests", ".tests_src_dir", ".trigger_tests")]
    print(f"{name}: hidden {len(c.hidden_tests)}/{len(relevant)} relevant classes, "
          f"{len(remaining)} test files remain; trigger classes {sorted(trigger_classes)}")
    assert not left, f"{name}: relevant tests still in the snapshot: {left}"
    assert sorted(c.hidden_tests) == relevant
    assert remaining, f"{name}: no test file left at all"
    assert not in_container, f"{name}: relevant tests missing from the container checkout: {in_container}"
    assert not leftovers, f"{name}: export files copied into the snapshot: {leftovers}"
    shutil.rmtree(snap.parent)

print("\nhidden-test checks passed")
