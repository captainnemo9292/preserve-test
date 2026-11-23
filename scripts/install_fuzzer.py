#!/usr/bin/env python3
"""Once: install the fuzzer changes into the Poracle container (poracle-reproduce).

1. The condition-aware differential fuzzer: the files changed in rlaaudrb/poracle-cond-aware:v1
   (the image must be pulled) are copied over the container's, unchanged:
     JQF      PoracleGuidance, ZestCLI2, InRangeFactory, FuzzStatement, Log (modified);
              CriticalBiasedInRange, CriticalValuePool (new)
     Poracle  src/main.py, src/poracle.py (modified); src/extract_critical_values.py (new)
2. Our Log fix, on top of that Log.java: logIn/logOut(Object first, Object... rest) only handled
   String, boxed primitives, Character, Boolean and Object[]. A null, a primitive array or any
   other object threw; in logIn that stops the WHOLE fuzzing run (FuzzStatement calls it on every
   generated argument), in logOut the input is silently ignored. Now: null -> "null", primitive
   arrays element by element, other objects -> String.valueOf(obj).
3. JQF is rebuilt as in that image (mvn -pl fuzz -am install) and the Log classes are synced
   into the other jar copies.

    python scripts/install_fuzzer.py            # install
    python scripts/install_fuzzer.py --restore  # back to the original Poracle

The original files are kept in /poracle/modules/JQF/.preserve-backup/src in the container.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from preserve.config import CONTAINER  # noqa: E402

COND_AWARE_IMAGE = "rlaaudrb/poracle-cond-aware:v1"
JQF = "/poracle/modules/JQF"
JDK = "/usr/lib/jvm/java-1.8.0-openjdk-amd64/bin"
BACKUP = f"{JQF}/.preserve-backup"
LOG_JAVA = f"{JQF}/log/src/main/java/anonymous/log/Log.java"
FUZZ_SRC = f"{JQF}/fuzz/src/main/java"

MODIFIED = [
    f"{FUZZ_SRC}/edu/berkeley/cs/jqf/fuzz/reach/PoracleGuidance.java",
    f"{FUZZ_SRC}/edu/berkeley/cs/jqf/fuzz/ei/ZestCLI2.java",
    f"{FUZZ_SRC}/anonymous/fuzz/generator/InRangeFactory.java",
    f"{FUZZ_SRC}/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java",
    LOG_JAVA,
    "/poracle-experiments/src/main.py",
    "/poracle-experiments/src/poracle.py",
]
NEW = [
    f"{FUZZ_SRC}/anonymous/fuzz/generator/CriticalBiasedInRange.java",
    f"{FUZZ_SRC}/edu/berkeley/cs/jqf/fuzz/util/CriticalValuePool.java",
    "/poracle-experiments/src/extract_critical_values.py",
]
# copies of the Log classes that mvn -pl fuzz -am does not refresh
LOG_JAR_COPIES = [
    f"{JQF}/fuzz/target/dependency/jqf-log-1.0-SNAPSHOT.jar",
    f"{JQF}/aspect/target/dependency/jqf-log-1.0-SNAPSHOT.jar",
    f"{JQF}/examples/target/dependency/jqf-log-1.0-SNAPSHOT.jar",
    f"{JQF}/instrument/target/dependency/jqf-log-1.0-SNAPSHOT.jar",
]

# our Log fix: (original, replacement) pairs; each original must occur exactly once
LOG_EDITS = [
    # --- logIn ---
    ("""    public static void logIn(Object first, Object ... rest) {
        if (first instanceof  String) {""",
     """    public static void logIn(Object first, Object ... rest) {
        if (first == null) {                                  // preserve-testing
            logIn("null");
        } else if (first instanceof  String) {"""),
    ("""        } else if (first.getClass().isArray()) {
            logInArray((Object[]) first);
        } else {
            throw new RuntimeException("Unexpected type: " + first.getClass());
        }""",
     """        } else if (first.getClass().isArray()) {
            logInArray(boxArray(first));                      // preserve-testing: also primitive arrays
        } else {
            logIn(String.valueOf(first));                     // preserve-testing: was "Unexpected type"
        }"""),
    # --- logOut ---
    ("""        } else if (first.getClass().isArray()) {
            logOutArray((Object[]) first);
        } else {
            throw new RuntimeException("Unexpected type: " + first.getClass());
        }""",
     """        } else if (first.getClass().isArray()) {
            logOutArray(boxArray(first));                     // preserve-testing: also primitive arrays
        } else {
            logOut(String.valueOf(first));                    // preserve-testing: was "Unexpected type"
        }"""),
    # --- helper ---
    ("""    public static <T> void logInArray(T[] arr) {""",
     """    /** preserve-testing: Object[] as is; primitive arrays boxed element by element. */
    private static Object[] boxArray(Object arr) {
        if (arr instanceof Object[]) return (Object[]) arr;
        int n = java.lang.reflect.Array.getLength(arr);
        Object[] boxed = new Object[n];
        for (int i = 0; i < n; i++) boxed[i] = java.lang.reflect.Array.get(arr, i);
        return boxed;
    }

    public static <T> void logInArray(T[] arr) {"""),
]


def sh(script: str, check: bool = True) -> str:
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "bash", "-s"], input=script,
                       capture_output=True, text=True)
    if check and p.returncode != 0:
        sys.exit(f"failed:\n{script}\n{p.stdout[-3000:]}\n{p.stderr[-3000:]}")
    return p.stdout


def backup_originals() -> None:
    """Keep the container's original files, once. (An earlier version of this script kept the
    original Log.java at BACKUP/Log.java while the live one was already patched.)"""
    lines = [f"mkdir -p {BACKUP}/src$(dirname {f}) && cp {f} {BACKUP}/src{f}" for f in MODIFIED]
    sh(f"set -e\nif [ ! -d {BACKUP}/src ]; then\n" + "\n".join(lines) + "\n"
       f"[ -f {BACKUP}/Log.java ] && cp {BACKUP}/Log.java {BACKUP}/src{LOG_JAVA}\nfi\ntrue\n")


def build() -> None:
    """Rebuild JQF's log and fuzz modules, then sync the Log classes into the other jar copies."""
    print("rebuilding JQF (mvn -pl fuzz -am install) ...", flush=True)
    sh(f"set -e\ncd {JQF}\n"
       "mvn -o -pl fuzz -am install -DskipTests -Drat.skip=true -q || "
       "mvn -pl fuzz -am install -DskipTests -Drat.skip=true -q\n"
       f"cd {JQF}/log/target/classes\n"
       + "\n".join(f"{JDK}/jar uf {j} anonymous/log/*.class" for j in LOG_JAR_COPIES))


def install() -> None:
    backup_originals()
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["docker", "create", "--name", "pt-cond-aware-src", COND_AWARE_IMAGE],
                       check=True, capture_output=True)
        try:
            for f in MODIFIED + NEW:
                local = Path(tmp) / Path(f).name
                subprocess.run(["docker", "cp", f"pt-cond-aware-src:{f}", str(local)], check=True)
                if f == LOG_JAVA:
                    src = local.read_text()
                    for old, new in LOG_EDITS:
                        if src.count(old) != 1:
                            sys.exit(f"Log.java: edit anchor found {src.count(old)} times:\n{old}")
                        src = src.replace(old, new)
                    local.write_text(src)
                subprocess.run(["docker", "cp", str(local), f"{CONTAINER}:{f}"], check=True)
        finally:
            subprocess.run(["docker", "rm", "pt-cond-aware-src"], capture_output=True)
    build()
    verify(installed=True)


def restore() -> None:
    sh("set -e\n" + "\n".join(f"cp {BACKUP}/src{f} {f}" for f in MODIFIED) + "\n"
       + "\n".join(f"rm -f {f}" for f in NEW))
    build()
    verify(installed=False)


def verify(installed: bool) -> None:
    """Every compiled copy must (not) contain the condition-aware bias and our Log fix."""
    zest_cli = f"{JQF}/fuzz/target/jqf-fuzz-1.5-SNAPSHOT-zest-cli.jar"
    checks = [("maybeBias", "anonymous/fuzz/generator/InRangeFactory.class", f"{JQF}/fuzz/target/classes"),
              ("maybeBias", "anonymous/fuzz/generator/InRangeFactory.class", zest_cli),
              ("boxArray", "anonymous/log/Log.class", f"{JQF}/log/target/classes"),
              ("boxArray", "anonymous/log/Log.class", f"{JQF}/log/target/jqf-log-1.0-SNAPSHOT.jar"),
              ("boxArray", "anonymous/log/Log.class", zest_cli)]
    checks += [("boxArray", "anonymous/log/Log.class", j) for j in LOG_JAR_COPIES]
    ok = True
    for symbol, cls, where in checks:
        name = cls[:-6].replace("/", ".")
        n = sh(f"{JDK}/javap -p -cp {where} {name} | grep -c {symbol}", check=False).strip()
        good = (n not in ("", "0")) == installed
        ok &= good
        print(f"{'ok ' if good else 'BAD'} {symbol:9s} {'present' if n not in ('', '0') else 'absent '}  {where}")
    flag = sh("grep -c -- --cond-aware /poracle-experiments/src/main.py", check=False).strip()
    good = (flag not in ("", "0")) == installed
    print(f"{'ok ' if good else 'BAD'} --cond-aware {'present' if flag not in ('', '0') else 'absent '}  /poracle-experiments/src/main.py")
    if not (ok and good):
        sys.exit("verification failed")
    print("installed" if installed else "restored the original Poracle")


if __name__ == "__main__":
    restore() if "--restore" in sys.argv else install()
