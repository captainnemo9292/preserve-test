"""Format check + derivation checks.

Run: python checks/04_derive.py [--compile]
  --compile  also compiles the derived file in a Math-35 checkout in the container
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from preserve.derive import derive, find_method, format_problems

FIX = Path(__file__).parent / "fixtures"
SRC = (FIX / "repro_math35.java").read_text()
REPRO, PRESERVE = "testConstructorTooLow_repro", "testConstructorTooLow"

# --- the good fixture is derivable ------------------------------------------------
assert format_problems(SRC, REPRO) == [], format_problems(SRC, REPRO)
out = derive(SRC, REPRO, PRESERVE)
print(out[out.index("public void testConstructorTooLow("):])

body = find_method(out, PRESERVE).body(out)
assert "boolean preserveIf = !buggyIf;" in body
assert "assumeTrue(preserveIf);" in body and "assumeTrue(buggyIf)" not in body
assert "Log.logOutIf(preserveIf, () -> new String[]{ observed" in body
assert "assertEquals" not in body                      # assertions removed
assert body.index("try {") < body.index("logOutIf(preserveIf, () -> new String[]{ observed")
assert "catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException" in body
assert find_method(out, REPRO).body(out) == find_method(SRC, REPRO).body(SRC)   # repro unchanged
assert out.count("@Fuzz") == 2

# --- malformed variants are rejected with a reason ---------------------------------
def variant(old: str, new: str) -> str:
    assert old in SRC, old
    return SRC.replace(old, new, 1)


# --- local variable declarations may follow the log line, and are left out of the derivation ---
LOG_LINE = 'Log.logOutIf(true, () -> new String[]{ observed, "size=" + size });'
DECLS = ('\n        final long expected = Long.parseLong("-".substring(0, 1));'      # throws when run
         '\n        java.util.List<java.util.Map<String, Integer>> seen = new java.util.ArrayList<>();'
         '\n        int[] counts = new int[]{ size }, more = { 1 };'
         '\n        String note = rate > 1 ? "high" : "low"; // a comment'
         '\n        Runnable check = () -> { int k = 0; k++; };'
         '\n        Object o = new Object() { public String toString() { return "x"; } };')
with_decls = variant(LOG_LINE, LOG_LINE + DECLS)
assert format_problems(with_decls, REPRO) == [], format_problems(with_decls, REPRO)
derived = derive(with_decls, REPRO, PRESERVE)
pbody = find_method(derived, PRESERVE).body(derived)
for gone in ("expected", "seen", "counts", "note", "Runnable", "new Object()", "assertEquals"):
    assert gone not in pbody, f"{gone} should not be in the preservation method"
assert "logOutIf(preserveIf" in pbody
assert DECLS.strip().splitlines()[0].strip() in find_method(derived, REPRO).body(derived)   # repro keeps them
# declarations between the assertions are fine too
between = variant('"OutOfRangeException", outcome);',
                  '"OutOfRangeException", outcome);\n        int n = size + 1;\n        assertTrue(n > size);')
assert format_problems(between, REPRO) == [], format_problems(between, REPRO)
print("declarations after the log line: accepted, and left out of the preservation method")

bad = {
    "missing buggyIf": variant("boolean buggyIf = rate < 0 || rate > 1;", "boolean other = rate < 0;"),
    "assumes swapped": variant("assumeTrue(validIf);\n        assumeTrue(buggyIf);",
                               "assumeTrue(buggyIf);\n        assumeTrue(validIf);"),
    "no logging": variant('Log.logOutIf(true, () -> new String[]{ observed, "size=" + size });', ""),
    "logging inside block": variant('Log.logOutIf(true, () -> new String[]{ observed, "size=" + size });',
                                    'if (size > 0) { Log.logOutIf(true, () -> new String[]{ observed }); }'),
    "statement after asserts": variant('"OutOfRangeException", outcome);',
                                       '"OutOfRangeException", outcome);\n        System.out.println(outcome);'),
    "param without range": variant("@InRange(minInt = 1, maxInt = 50) int size", "int size"),
    "logs false": variant("Log.logOutIf(true,", "Log.logOutIf(false,"),
    "constant buggyIf": variant("boolean buggyIf = rate < 0 || rate > 1;", "boolean buggyIf = true;"),
    "call after log": variant(LOG_LINE, LOG_LINE + "\n        new ElitisticListPopulation(size, rate);"),
    "assignment after log": variant(LOG_LINE, LOG_LINE + '\n        outcome = "x";'),
    "if after log": variant(LOG_LINE, LOG_LINE + "\n        if (size > 0) { assertTrue(true); }"),
    "return after log": variant(LOG_LINE, LOG_LINE + "\n        return;"),
    "declarations, no assert": variant(
        '\n        assertEquals("expected OutOfRangeException for rate " + rate + " }", "OutOfRangeException", outcome);',
        "\n        int n = size;"),
}
for name, src in bad.items():
    problems = format_problems(src, REPRO)
    print(f"{name:24s} -> {problems}")
    assert problems, name

print("\nall derive checks passed")

if "--compile" in sys.argv:
    from preserve.bugs import Bug
    from preserve.container import Checkout
    c = Checkout(Bug.parse("Math-35")).prepare()
    c.env.write(f"{c.dir}/{c.test_dir}/org/apache/commons/math3/genetics/JQF_ElitisticListPopulationTest.java",
                out.encode())
    ok, errors = c.compile_tests()
    print("derived file compiles in the container:", ok, errors[:2000])
    assert ok
