"""Poracle's buggy-version steps in the container: checkout, build delta, compile, trigger tests.

Run: python checks/02_checkout.py [Math-35]
"""

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from preserve.bugs import Bug  # noqa: E402
from preserve.container import Checkout, jqf_test_unit  # noqa: E402

c = Checkout(Bug.parse(sys.argv[1] if len(sys.argv) > 1 else "Math-35")).prepare()
tests = c.trigger_tests()
print("trigger tests:", tests)
print("test unit    :", jqf_test_unit(tests[0]))
print("classpath    :", c.classpath)
print("test dir     :", c.test_dir)

# Poracle's hand-written JQF tests must not be in the checkout
leaked = c.env.exec_cmd(f"find {c.dir} -name 'JQF_*.java'").stdout.strip()
assert leaked == "", f"Poracle's JQF test leaked into the checkout: {leaked}"

ok, errors = c.compile_tests()
assert ok, errors
snap = c.snapshot_to(Path(tempfile.mkdtemp(prefix="snap_")) / "source")
print("snapshot     :", snap, sorted(x.name for x in snap.iterdir())[:8])
print("\ncheckout checks passed")
