#!/usr/bin/env python3
"""Add preserve-test's boxArray fix to a VANILLA Log.java.

Standalone — does NOT require preserve-test's install_fuzzer.py or cond-aware.
Idempotent: if boxArray already present, no-op.

Changes:
  - logOut(Object first, ...): primitive arrays via boxArray, non-boxed → String.valueOf, null-safe
  - logIn(Object first, ...):  same
  - Private static Object[] boxArray(Object arr) helper added once

These three changes prevent GuidanceException on harnesses that log non-boxed
objects (preserve-test agent generates such tests). Without this, the Zest
loop dies the moment a harness emits a null / primitive array / custom object.
"""
import sys

LOG = "/poracle/modules/JQF/log/src/main/java/anonymous/log/Log.java"

VANILLA_LOGOUT_CATCHALL = """        } else if (first.getClass().isArray()) {
            logOutArray((Object[]) first);
        } else {
            throw new RuntimeException("Unexpected type: " + first.getClass());
        }"""
FIXED_LOGOUT_CATCHALL = """        } else if (first.getClass().isArray()) {
            logOutArray(boxArray(first));                     // preserve-testing: also primitive arrays
        } else {
            logOut(String.valueOf(first));                    // preserve-testing: was "Unexpected type"
        }"""

VANILLA_LOGIN_HEADER = "    public static void logIn(Object first, Object ... rest) {\n        if (first instanceof  String) {"
FIXED_LOGIN_HEADER   = "    public static void logIn(Object first, Object ... rest) {\n        if (first == null) {\n            logIn(\"null\");\n        } else if (first instanceof String) {"

VANILLA_LOGIN_CATCHALL = """        } else if (first.getClass().isArray()) {
            logInArray((Object[]) first);
        } else {
            throw new RuntimeException("Unexpected type: " + first.getClass());
        }"""
FIXED_LOGIN_CATCHALL = """        } else if (first.getClass().isArray()) {
            logInArray(boxArray(first));                      // preserve-testing: also primitive arrays
        } else {
            logIn(String.valueOf(first));                     // preserve-testing: was "Unexpected type"
        }"""

BOXARRAY_HELPER = """
    /** preserve-testing: Object[] as is; primitive arrays boxed element by element. */
    private static Object[] boxArray(Object arr) {
        if (arr instanceof Object[]) return (Object[]) arr;
        int n = java.lang.reflect.Array.getLength(arr);
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = java.lang.reflect.Array.get(arr, i);
        return out;
    }
"""


def main() -> int:
    src = open(LOG, encoding="utf-8").read()
    if "boxArray" in src:
        print("Log.java: boxArray already present — skip")
        return 0

    missing = []
    for needle, name in [(VANILLA_LOGOUT_CATCHALL, "logOut catch-all"),
                         (VANILLA_LOGIN_HEADER, "logIn header"),
                         (VANILLA_LOGIN_CATCHALL, "logIn catch-all")]:
        if needle not in src:
            missing.append(name)
    if missing:
        print(f"ERROR: vanilla anchor(s) not found: {missing}", file=sys.stderr)
        print("Log.java may already have different modifications. Inspect manually.", file=sys.stderr)
        return 2

    src = src.replace(VANILLA_LOGOUT_CATCHALL, FIXED_LOGOUT_CATCHALL, 1)
    src = src.replace(VANILLA_LOGIN_HEADER, FIXED_LOGIN_HEADER, 1)
    src = src.replace(VANILLA_LOGIN_CATCHALL, FIXED_LOGIN_CATCHALL, 1)
    # Insert boxArray helper before the final closing brace of the file
    last_brace = src.rfind("\n}")
    if last_brace < 0:
        print("ERROR: closing brace not found", file=sys.stderr); return 3
    src = src[:last_brace] + BOXARRAY_HELPER + src[last_brace:]

    open(LOG, "w", encoding="utf-8").write(src)
    print("Log.java: boxArray fix applied (null-safe logIn/logOut + boxArray helper)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
