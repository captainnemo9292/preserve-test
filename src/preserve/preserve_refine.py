"""Pass 2: refine the DERIVED preservation method's logOut based on tier feedback.

After Pass 1 (ReproAgent) writes the repro method and derive.py produces the full
JQF_<Class>.java (repro + mechanically derived preserve), this pass runs a small
agent loop that:

  1. fuzzes the derived preserve method on the BUGGY version only
  2. collects features.json (same collector as agent.py uses)
  3. builds tier feedback (essential + supplementary only)
  4. asks the LLM to rewrite the preserve method's logOutIf call so the logged
     values discriminate patched vs buggy behavior better

The LLM receives:
  - the current FULL test file (repro + preserve)
  - the preserve method name (so it knows which block to edit)
  - tier feedback computed from the buggy-only preserve fuzz run
  - 4 hard rules:
      R1. Only modify the preserve method's logOutIf arguments
      R2. Do NOT change buggyIf / preserveIf predicates
      R3. Do NOT change repro method (testBugReproduction)
      R4. Keep the method signature and parameters identical

The LLM never sees the developer fix. Tier feedback is based on buggy-only fuzz,
so fix-side outputs are NEVER used — confinement intact.
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from preserve import prompts as P
from preserve import tiered_feedback as TF
from preserve.agent import _tf_flags
from preserve.container import DockerEnv, Checkout
from preserve.derive import format_problems
from preserve.jqf import fuzz, install_test
from preserve.llm import ClaudeCLI, Trace, VerboseLog


@dataclass
class PreserveTarget:
    env: DockerEnv
    compile_fn: Any       # Checkout.compile_tests
    path: str             # the test file path in the container
    path_rel: str
    test_class: str
    preserve_method: str
    classpath: str
    workdir: str
    fuzz_seconds: int


class PreserveRefiner:
    """Iteratively refine the preserve method's logOut values via tier feedback."""

    def __init__(self, target: PreserveTarget, llm: ClaudeCLI, log: VerboseLog, trace: Trace,
                 max_iterations: int = 3, max_llm_calls: int = 5):
        self.t = target
        self.llm = llm
        self.log = log
        self.trace = trace
        self.max_iter = max_iterations
        self.max_calls = max_llm_calls
        self.llm_calls = 0

    def refine(self, initial_test_src: str) -> dict:
        """Run up to max_iterations of refinement. Returns {final_src, iterations_run, features_history}."""
        test_src = initial_test_src
        features_history: list[dict] = []
        self.log.section("PRESERVE REFINE START",
                         f"target preserve_method={self.t.preserve_method}, "
                         f"max_iter={self.max_iter}")

        for i in range(self.max_iter):
            # 1. install current preserve test, compile
            errors = install_test(self.t.env, test_src, self.t.path, self.t.compile_fn,
                                  self.log.section)
            if errors is not None:
                self.log.section(f"REFINE iter {i+1}: compile error — abort",
                                 errors[:2000])
                break

            # 2. fuzz the preserve method on buggy only (no differential)
            run = fuzz(self.t.env, self.t.test_class, self.t.preserve_method,
                       self.t.classpath, f"{self.t.workdir}/fuzz-preserve-refine-{i+1}",
                       self.t.fuzz_seconds, classify=50, log=self.log.section)
            features = run.features or {}
            features_history.append({"iteration": i+1,
                                     "features_schema": features.get("schema"),
                                     "trials": run.trials,
                                     "valid": run.valid,
                                     "unique_output_shapes_org": features.get("unique_output_shapes_org"),
                                     "output_shape_tokens": features.get("output_shape_tokens")})

            # 3. If log is already highly discriminating, stop early
            shapes = features.get("unique_output_shapes_org", 0)
            valid = features.get("valid_iterations", 0)
            if valid > 100 and shapes > 50:
                self.log.section(f"REFINE iter {i+1}: log is already discriminating "
                                 f"(shapes={shapes}, valid={valid}) — stop",
                                 features_history[-1])
                break

            # 4. Build tier feedback block (buggy-only signals)
            tier_block = TF.build(features, **_tf_flags())
            self.log.section(f"REFINE iter {i+1} TIER FEEDBACK (buggy preserve fuzz)",
                             tier_block or "(empty)")

            # 5. LLM call: ask for a better preserve logOut
            if self.llm_calls >= self.max_calls:
                self.log.section("REFINE: LLM budget exhausted", f"calls={self.llm_calls}")
                break
            prompt = P.PRESERVE_REFINE.format(
                test=test_src,
                preserve_method=self.t.preserve_method,
                feedback=tier_block or "(no tier feedback available)",
            )
            reply = self.llm.ask(prompt, schema=_TestSchema)
            self.llm_calls += 1
            if reply.parsed is None:
                self.log.section(f"REFINE iter {i+1}: LLM returned no structured output",
                                 reply.text[:500])
                break
            new_src = getattr(reply.parsed, "test", "") or ""
            if not new_src:
                self.log.section(f"REFINE iter {i+1}: LLM returned empty — stop", "")
                break

            # 6. Validate structure (same checks as ReproAgent uses)
            problems = format_problems(new_src, "testBugReproduction")
            if problems:
                self.log.section(f"REFINE iter {i+1}: structure problems — reject and stop",
                                 "\n".join(problems))
                break
            # 7. Accept
            test_src = new_src
            self.log.section(f"REFINE iter {i+1}: new test accepted",
                             f"len={len(test_src)}")

        return {
            "final_src": test_src,
            "iterations_run": len(features_history),
            "llm_calls": self.llm_calls,
            "features_history": features_history,
        }


# ---- minimal pydantic-ish schema for Claude CLI structured output ----

try:
    from pydantic import BaseModel, Field

    class _TestSchema(BaseModel):
        test: str = Field(description="Full updated test source (repro method unchanged, "
                                      "only the preserve method's logOutIf arguments edited).")

except ImportError:
    class _TestSchema:
        def __init__(self):
            self.test = ""
