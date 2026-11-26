"""Step 1: write a bug-reproducing JQF test from the bug report.

This is AutoCodeSherpa's PBT-generation graph (its nodes, routing and refinement loop), with
JQF in place of Hypothesis and the Claude CLI as the model. The test is refined on the buggy
version only:

    generalize ─► symbolize ─► execute ─► check_inputs ─► validate ─► success
                      ▲           │            │             │
                      │           │            │             └─ some inputs pass ─► filter_passing ─► execute
                      └───────────┴────────────┴─ feedback: bad structure / compile error / no input
                                                  checked / test passed / wrong exception /
                                                  not relevant / validation issue

    generalize      bug report -> summary + concrete examples
    symbolize       write the test (the model may read the project with Read/Grep/Glob)
    execute         structure check, compile, fuzz on the buggy version, group the inputs by outcome
    check_inputs    do the failures reproduce the reported bug, through an assertion?
    validate        no mocking, no hard-coded failure, proper equality checks
    filter_passing  narrow buggyIf to exclude inputs that pass

It stops with a failure when the LLM-call budget is spent.
"""

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, TypedDict

from langgraph.graph import END, StateGraph
from pydantic import BaseModel, ConfigDict, Field

import os
from preserve import prompts as P
from preserve import tiered_feedback as TF  # tier feedback (see modify.md)
from preserve.container import DockerEnv
from preserve.derive import format_problems
from preserve.jqf import JqfRun, exception_types, fuzz, install_test, is_assertion_failure
from preserve.llm import READ_ONLY_TOOLS, ClaudeCLI, Trace, VerboseLog

def _tf_flags():
    """Read tier on/off from env vars so ablation flips without code edits."""
    def _on(name: str, default: str) -> bool:
        return os.environ.get(name, default) not in ("", "0", "false", "False")
    return dict(
        essential_on=_on("PRESERVE_FEEDBACK_ESSENTIAL", "1"),
        supplementary_on=_on("PRESERVE_FEEDBACK_SUPPLEMENTARY", "1"),
        bulk_on=_on("PRESERVE_FEEDBACK_BULK", "0"),
    )

EXPLORE_MAX_TURNS = 30          # turns the model may take in one call that has tools
CLASSIFY_INPUTS = 5000          # fuzzed inputs replayed per execution to count passing and failing ones
GRAPH_RECURSION_LIMIT = 999     # effectively unlimited; the LLM-call budget ends the loop


# --- structured outputs ---------------------------------------------------------------------

class ConcreteExample(BaseModel):
    model_config = ConfigDict(extra="forbid")
    input: str = Field(description="The input that triggers the bug")
    expected: str = Field(description="The expected (correct) behavior")
    actual: str = Field(description="The actual (buggy) behavior")


class GeneralizeOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    issue_gist: str = Field(description="1-2 sentence summary of the bug")
    concrete_examples: list[ConcreteExample] = Field(description="Concrete examples showing the bug")


class TestOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pbt: str = Field(description="Full source code of the test file")


class ValidationCheck(BaseModel):
    model_config = ConfigDict(extra="forbid")
    is_valid: bool = Field(description="True if the check passes (no issues found), False if issues exist")
    reasoning: str = Field(description="Brief explanation of the validation result")


class RelevanceCheck(BaseModel):
    model_config = ConfigDict(extra="forbid")
    is_relevant: bool = Field(description="True if the failing inputs reproduce the reported bug")
    reasoning: str = Field(description="Explanation of why the failures are or are not relevant")


# --- inputs and state -----------------------------------------------------------------------

@dataclass
class TestTarget:
    """Where the test goes in the container and how it is compiled and run."""
    env: DockerEnv
    compile_fn: Callable[[], tuple[bool, str]]
    path: str               # absolute path of the test file in the container
    path_rel: str           # the same, relative to the project root (shown to the model)
    test_class: str         # fully qualified, e.g. org.x.JQF_FooTest
    test_method: str        # config.REPRO_METHOD
    classpath: str
    workdir: str            # a directory in the container for fuzzing output
    fuzz_seconds: int = 15


class State(TypedDict, total=False):
    issue: str
    issue_gist: str
    concrete_examples: list[dict[str, Any]]
    test: str                   # current source of the test file
    run: JqfRun | None          # last execution
    exceptions: list[str]       # exception types the failing trials raised
    needs_rewrite: bool         # the last execution checked no input (feedback is pending)
    irrelevant: bool            # the failures do not reproduce the reported bug
    irrelevant_info: str
    validation_issue: str
    success: bool
    failure_reason: str


class ReproAgent:
    def __init__(self, root: str, target: TestTarget, model: str, log: VerboseLog, max_llm_calls: int = 50,
                 trace: Trace | None = None, attempts_dir: Path | None = None):
        self.root = root                    # host snapshot of the buggy checkout
        self.target = target
        self.log = log
        self.trace = trace or Trace()
        self.attempts_dir = attempts_dir    # every version of the test the model writes is kept here
        self.attempts = 0
        self.max_llm_calls = max_llm_calls
        self.llm = ClaudeCLI(model, root, P.SYSTEM, log, self.trace)
        self.llm_calls = 0
        self.pending: list[str] = []        # feedback that goes out with the next prompt
        self._node = 0

    def run(self, issue: str) -> State:
        return self._graph().invoke({"issue": issue}, {"recursion_limit": GRAPH_RECURSION_LIMIT})  # type: ignore[return-value]

    # --- helpers ----------------------------------------------------------------------------

    def _ask(self, prompt: str, schema, explore: bool = False):
        """Send the pending feedback and `prompt`; return the structured answer. A call with
        tools counts every turn against the budget (as AutoCodeSherpa's ReAct steps do)."""
        text = "\n\n".join(self.pending + [prompt])
        self.pending = []
        reply = self.llm.ask(text, schema, tools=READ_ONLY_TOOLS if explore else "",
                             max_turns=EXPLORE_MAX_TURNS if explore else 3)
        self.llm_calls += reply.turns if explore else 1
        return reply.parsed

    def _budget_left(self) -> bool:
        return self.llm_calls < self.max_llm_calls

    def _write_test(self, prompt: str) -> State:
        out = self._ask(prompt, TestOutput, explore=True)
        if not self._budget_left():
            return {"failure_reason": f"LLM call budget exhausted ({self.llm_calls} calls)"}
        return {"test": out.pbt}

    # --- nodes ------------------------------------------------------------------------------

    def generalize(self, s: State) -> State:
        out = self._ask(P.GENERALIZE.format(issue=s["issue"]), GeneralizeOutput)
        return {"issue_gist": out.issue_gist,
                "concrete_examples": [ex.model_dump() for ex in out.concrete_examples]}

    def symbolize(self, s: State) -> State:
        if not s.get("issue_gist") or not s.get("concrete_examples"):
            raise ValueError("issue_gist and concrete_examples are required")
        t = self.target
        package, class_simple = t.test_class.rsplit(".", 1)
        return self._write_test(P.SYMBOLIZE.format(
            root=self.root, test_path=t.path_rel, test_class=t.test_class, test_method=t.test_method,
            issue_gist=s["issue_gist"], concrete_examples=s["concrete_examples"],
            structure=P.TEST_STRUCTURE.format(package=package, class_simple=class_simple,
                                              method=t.test_method)))

    def execute(self, s: State) -> State:
        """Run the test on the buggy version. When no input was checked, the reason is queued
        as feedback and the test goes back to symbolize."""
        t, test = self.target, s["test"]
        rewrite: State = {"run": None, "exceptions": [], "needs_rewrite": True}
        self.log.section(f"TEST CODE ({t.test_class})", test)
        self.attempts += 1
        attempt: dict = {"attempt": self.attempts, "node": self._node}
        if self.attempts_dir:
            self.attempts_dir.mkdir(parents=True, exist_ok=True)
            (self.attempts_dir / f"{self.attempts:03d}.java").write_text(test)
            attempt["file"] = f"{self.attempts_dir.name}/{self.attempts:03d}.java"

        # the structure step 2 derives the preservation test from
        problems = format_problems(test, t.test_method)
        if problems:
            self.log.section("STRUCTURE CHECK FAILED", "\n".join(problems))
            self.pending.append(P.BAD_STRUCTURE.format(problems="\n".join(f"- {p}" for p in problems)))
            self.trace.write("execution", **attempt, outcome="bad structure", problems=problems)
            return rewrite

        errors = install_test(t.env, test, t.path, t.compile_fn, self.log.section)
        if errors is not None:
            self.pending.append(P.COMPILE_ERROR.format(errors=errors))
            self.trace.write("execution", **attempt, outcome="compile error", errors=errors)
            return rewrite
        run = fuzz(t.env, t.test_class, t.test_method, t.classpath, f"{t.workdir}/fuzz-agent",
                   t.fuzz_seconds, classify=CLASSIFY_INPUTS, log=self.log.section)
        attempt.update(fuzz_seconds=t.fuzz_seconds, passed_assume=run.valid, **run.summary())
        if not run.groups:
            reason = (P.NO_INPUT_GENERATOR_FAILED.format(error=run.guidance_error) if run.guidance_error
                      else P.NO_INPUT_NONE_MATCHED.format(trials=run.trials))
            self.pending.append(reason)
            self._maybe_append_tier_feedback(run)
            self.trace.write("execution", **attempt, outcome="no input checked", guidance_error=run.guidance_error)
            return rewrite

        exceptions: list[str] = []
        for trace in run.failing:
            exceptions += [e for e in exception_types(trace) if e not in exceptions]
        self._maybe_append_tier_feedback(run)
        self.trace.write("execution", **attempt, outcome="ran", exceptions=exceptions,
                         failing_examples=run.show_failing(), passing_examples=run.show_passing())
        return {"run": run, "exceptions": exceptions, "needs_rewrite": False}

    def _maybe_append_tier_feedback(self, run: JqfRun) -> None:
        """Append fuzzer-side tier feedback so it reaches the next LLM call.
        Attaches to the latest pending message when one exists; otherwise
        pushes a standalone message so downstream check_inputs / validate /
        filter_passing can chain their own feedback after it."""
        if run is None or not run.features:
            return
        try:
            block = TF.build(run.features, **_tf_flags())
        except Exception:
            return
        if not block:
            return
        if self.pending:
            self.pending[-1] = self.pending[-1] + "\n\n" + block
        else:
            self.pending.append(block)

    def check_inputs(self, s: State) -> State:
        """The failures must be assertion failures, and must reproduce the reported bug."""
        run = s["run"]
        assert run is not None
        failing = run.show_failing()
        unexpected = [e for e in s["exceptions"] if not is_assertion_failure(e)]
        if unexpected:
            self.pending.append(P.WRONG_EXCEPTION.format(
                failing=failing, raised=", ".join(s["exceptions"])))
            return {"irrelevant": True, "irrelevant_info":
                    f"Irrelevant exceptions raised: {', '.join(unexpected)}. Expected: AssertionError."}
        if not failing:
            return {"irrelevant": False, "irrelevant_info": ""}

        verdict = self._ask(P.RELEVANCE.format(issue=s["issue"], test=s["test"], failing=failing),
                            RelevanceCheck)
        if not verdict.is_relevant:
            self.pending.append(P.NOT_RELEVANT.format(
                failing=failing, reasoning=verdict.reasoning))
        return {"irrelevant": not verdict.is_relevant,
                "irrelevant_info": "" if verdict.is_relevant else verdict.reasoning}

    def validate(self, s: State) -> State:
        for label, prompt in [("Mocking/simulation detected", P.MOCKING_CHECK),
                              ("Hardcoded failure detected", P.HARDCODE_CHECK),
                              ("Improper equality check", P.EQUALITY_CHECK)]:
            check = self._ask(prompt.format(test=s["test"]), ValidationCheck)
            if not check.is_valid:
                if prompt is P.MOCKING_CHECK:
                    self.pending.append(P.USES_MOCKING)
                return {"validation_issue": f"{label}: {check.reasoning}"}
        return {"validation_issue": ""}

    def filter_passing(self, s: State) -> State:
        """Some inputs in buggyIf pass: have the model narrow buggyIf."""
        run = s["run"]
        assert run is not None
        return self._write_test(P.FILTER_PASSING.format(root=self.root, passing=run.show_passing()))

    def finalize_success(self, s: State) -> State:
        return {"success": True, "failure_reason": ""}

    def finalize_failure(self, s: State) -> State:
        reason = s.get("failure_reason")
        if not reason:
            spent = f"(after {self.llm_calls}/{self.max_llm_calls} LLM calls)"
            if not s.get("exceptions"):
                reason = f"The test did not fail on the buggy version {spent}"
            elif s.get("irrelevant"):
                reason = f"The failures do not reproduce the bug: {s.get('irrelevant_info', '')} {spent}"
            else:
                reason = f"LLM call budget exhausted {spent}"
        return {"success": False, "failure_reason": reason}

    # --- routing ----------------------------------------------------------------------------

    def _retry(self) -> str:
        return "symbolize" if self._budget_left() else "end_failure"

    def after_symbolize(self, s: State) -> str:
        return "end_failure" if s.get("failure_reason") or not s.get("test") else "execute"

    def after_execute(self, s: State) -> str:
        if s.get("failure_reason"):
            return "end_failure"
        if s["needs_rewrite"]:
            return self._retry()
        if not s["exceptions"]:
            # every checked input passed: show them with the values the test logged
            run = s["run"]
            assert run is not None
            result = (f"All {run.n_passing} checked inputs passed. Examples:\n    "
                      + run.show_passing(max_show=8))
            self.pending.append(P.TEST_PASSED.format(
                result=result, examples=_examples_reminder(s.get("concrete_examples", []))))
            return self._retry()
        return "check_inputs"

    def after_check_inputs(self, s: State) -> str:
        return self._retry() if s["irrelevant"] else "validate"

    def after_validate(self, s: State) -> str:
        if s["validation_issue"]:
            return self._retry()
        run = s["run"]
        if run is not None and run.passing:
            return "filter_passing" if self._budget_left() else "end_failure"
        return "success"

    # --- graph ------------------------------------------------------------------------------

    def _graph(self):
        g = StateGraph(State)
        for name in ["generalize", "symbolize", "execute", "check_inputs", "validate",
                     "filter_passing", "finalize_success", "finalize_failure"]:
            g.add_node(name, self._logged(name, getattr(self, name)))
        g.set_entry_point("generalize")
        g.add_edge("generalize", "symbolize")
        g.add_conditional_edges("symbolize", self.after_symbolize,
                                {"execute": "execute", "end_failure": "finalize_failure"})
        g.add_conditional_edges("execute", self.after_execute,
                                {"symbolize": "symbolize", "check_inputs": "check_inputs",
                                 "end_failure": "finalize_failure"})
        g.add_conditional_edges("check_inputs", self.after_check_inputs,
                                {"symbolize": "symbolize", "validate": "validate",
                                 "end_failure": "finalize_failure"})
        g.add_conditional_edges("validate", self.after_validate,
                                {"symbolize": "symbolize", "filter_passing": "filter_passing",
                                 "success": "finalize_success", "end_failure": "finalize_failure"})
        g.add_edge("filter_passing", "execute")
        g.add_edge("finalize_success", END)
        g.add_edge("finalize_failure", END)
        return g.compile()

    def _logged(self, name: str, node):
        def wrapper(s: State) -> State:
            self._node += 1
            self.llm.step = f"{self._node:03d}_{name}"
            self.log.section(f"NODE {self._node:03d} {name}")
            began = time.time()
            update = node(s)
            # what the node decided; the test itself is in attempts/, the run in its "execution" record
            self.trace.write("node", node=self._node, name=name, seconds=round(time.time() - began, 1),
                             llm_calls_so_far=self.llm_calls,
                             update={k: v for k, v in update.items() if k not in ("test", "run")})
            return update
        return wrapper


def _examples_reminder(examples: list[dict]) -> str:
    if not examples:
        return ""
    parts = []
    for i, ex in enumerate(examples, 1):
        lines = [f"Example {i}:"]
        if ex.get("input"):
            lines.append(f"  Input: {ex['input'][:200]}...")
        if ex.get("expected"):
            lines.append(f"  Expected: {ex['expected'][:150]}...")
        if ex.get("actual"):
            lines.append(f"  Actual (buggy): {ex['actual'][:150]}...")
        parts.append("\n".join(lines))
    return P.EXAMPLES_REMINDER.format(examples="\n\n".join(parts))
