#!/usr/bin/env python3
"""Inject LLMFeatureCollector hooks into Log.java and FuzzStatement.java.

Run INSIDE the poracle container AFTER preserve-test's install_fuzzer.py has
installed the cond-aware changes. Idempotent — safe to re-run.

Targets:
  /poracle/modules/JQF/log/src/main/java/anonymous/log/Log.java
  /poracle/modules/JQF/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java

Changes:
  1. Log.java::LogResult  : public getOutputForOrg() / getOutputForPatch() getters
  2. FuzzStatement.java::evaluate()     : recordIteration hook (null args — single-version path)
  3. FuzzStatement.java::evaluateOrg()  : recordIteration hook (full args — differential path)
"""
import sys

LOG = "/poracle/modules/JQF/log/src/main/java/anonymous/log/Log.java"
FS = "/poracle/modules/JQF/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java"


def patch_log(src: str) -> str | None:
    if "getOutputForOrg" in src:
        return None
    needle = "private static String outputForPatch = null;"
    if needle not in src:
        sys.exit("Log.java anchor missing: 'private static String outputForPatch = null;'")
    getters = (
        "\n        public static String getOutputForOrg() { return outputForOrg; }"
        "\n        public static String getOutputForPatch() { return outputForPatch; }\n"
    )
    return src.replace(needle, needle + getters, 1)


def patch_fuzzstatement_evaluate(src: str) -> str:
    """Standard JUnit path (ZestDriver uses this). args is not in scope — pass null."""
    if src.count("LLMFeatureCollector.recordIteration") >= 2:
        return src
    needle = (
        "                // Inform guidance about the outcome of this trial\n"
        "                guidance.handleResult(result, error);"
    )
    if needle not in src:
        sys.exit("FuzzStatement.evaluate() anchor missing: 'guidance.handleResult(result, error);'")
    hook = (
        needle + "\n\n"
        "                try {\n"
        "                    anonymous.llmfeedback.LLMFeatureCollector.recordIteration(\n"
        "                            null,\n"
        "                            Log.LogResult.getOutputForOrg(),\n"
        "                            Log.LogResult.getOutputForPatch(),\n"
        "                            error,\n"
        "                            false,\n"
        "                            result == edu.berkeley.cs.jqf.fuzz.guidance.Result.INVALID);\n"
        "                } catch (Throwable __ignore) { }"
    )
    return src.replace(needle, hook, 1)


def patch_fuzzstatement_evaluate_org(src: str) -> str:
    """Poracle differential path (ZestCLI2 uses this). args in scope."""
    if src.count("LLMFeatureCollector.recordIteration") >= 2:
        return src
    needle = (
        "                if (guidance.shouldSaveInput(resultOfOrg, resultOfPatch)) {"
    )
    if needle not in src:
        # cond-aware sources may have a slight variant; try without the trailing newline/indent
        sys.exit("FuzzStatement.evaluateOrg() anchor missing: 'if (guidance.shouldSaveInput(resultOfOrg, resultOfPatch)) {'")
    hook = (
        "                try {\n"
        "                    anonymous.llmfeedback.LLMFeatureCollector.recordIteration(\n"
        "                            args,\n"
        "                            Log.LogResult.getOutputForOrg(),\n"
        "                            Log.LogResult.getOutputForPatch(),\n"
        "                            error,\n"
        "                            isDiffOutputFound(),\n"
        "                            !resultOfOrg.isInputNotIgnored());\n"
        "                } catch (Throwable __ignore) { }\n\n"
        + needle
    )
    return src.replace(needle, hook, 1)


def main() -> int:
    with open(LOG, encoding="utf-8") as fh:
        log_src = fh.read()
    new_log = patch_log(log_src)
    if new_log is not None:
        with open(LOG, "w", encoding="utf-8") as fh:
            fh.write(new_log)
        print("Log.java: getters added")
    else:
        print("Log.java: getters already present")

    with open(FS, encoding="utf-8") as fh:
        fs_src = fh.read()
    before = fs_src.count("LLMFeatureCollector.recordIteration")
    fs_src = patch_fuzzstatement_evaluate(fs_src)
    fs_src = patch_fuzzstatement_evaluate_org(fs_src)
    after = fs_src.count("LLMFeatureCollector.recordIteration")
    if after != before:
        with open(FS, "w", encoding="utf-8") as fh:
            fh.write(fs_src)
        print(f"FuzzStatement.java: hooks {before} -> {after}")
    else:
        print("FuzzStatement.java: hooks already present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
