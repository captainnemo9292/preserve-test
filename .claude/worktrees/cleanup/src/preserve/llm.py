"""The LLM: Claude Code CLI in headless mode (`claude -p`), one resumed session per bug.

The CLI keeps the conversation, so each call sends only the new prompt. A call can ask for a
structured answer (`schema`, a pydantic model) and can be given read-only tools to explore
the project snapshot (a ReAct step in one call).

Two records of a run: `VerboseLog`, the readable one, and `Trace`, one JSON object per line
with everything in it (every event the CLI streams: the model's text, its tool calls and their
results, its thinking blocks as far as the CLI delivers their text, and each call's duration,
token usage and cost).
"""

import json
import shlex
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

READ_ONLY_TOOLS = "Read,Grep,Glob"
GUARD = Path(__file__).with_name("guard.py")


def confine_settings(root: str) -> str:
    """CLI settings with a PreToolUse hook that keeps Read/Grep/Glob inside `root` (guard.py)."""
    command = f"{shlex.quote(sys.executable)} -I {shlex.quote(str(GUARD))} {shlex.quote(root)}"
    return json.dumps({"hooks": {"PreToolUse": [
        {"matcher": "Read|Grep|Glob", "hooks": [{"type": "command", "command": command}]}]}})


class ClaudeCLIError(Exception):
    pass


class VerboseLog:
    """Full, human-readable log of a run: every prompt, the model's text, tool calls and
    results, structured outputs, the test code, compile results and raw execution output.
    Written to `path`; echoed to stdout if `echo`."""

    def __init__(self, path: Path | str | None, echo: bool = False):
        self.path = Path(path) if path else None
        self.echo = echo
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text("")

    def section(self, title: str, body: Any = "") -> None:
        if not isinstance(body, str):
            body = json.dumps(body, indent=2, ensure_ascii=False, default=str)
        text = f"\n{'=' * 100}\n[{time.strftime('%H:%M:%S')}] {title}\n{'=' * 100}\n{body.rstrip()}\n"
        if self.path:
            with open(self.path, "a") as f:
                f.write(text)
        if self.echo:
            print(text, flush=True)


class Trace:
    """Machine-readable record of a run, appended to `path` as JSON lines: {"time", "type", ...}.
    A trace without a path records nothing."""

    def __init__(self, path: Path | str | None = None):
        self.path = Path(path) if path else None
        self.started = time.time()
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text("")

    def write(self, kind: str, **fields: Any) -> None:
        if self.path:
            record = {"time": time.strftime("%Y-%m-%dT%H:%M:%S"), "elapsed": round(time.time() - self.started, 3),
                      "type": kind, **fields}
            with open(self.path, "a") as f:
                f.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")


@dataclass
class Reply:
    text: str
    parsed: Any          # the `schema` instance, or None
    turns: int           # model turns this call took (tool use adds turns)


class ClaudeCLI:
    def __init__(self, model: str, workdir: str, system_prompt: str, log: VerboseLog, trace: Trace | None = None):
        self.model = model
        self.workdir = workdir            # the CLI's cwd: what its Read/Grep/Glob tools see
        self.system_prompt = system_prompt
        self.log = log
        self.trace = trace or Trace()
        self.calls: list[dict] = []       # one summary per call: step, seconds, turns, tokens, cost
        self.step = ""                    # label of the current workflow step, for the log
        self.session_id = str(uuid.uuid4())
        self.started = False
        self.cost_usd = 0.0               # the CLI reports the session's cumulative cost
        self.tokens = {"input": 0, "output": 0, "cache_write": 0, "cache_read": 0}

    def ask(self, prompt: str, schema=None, tools: str = "", max_turns: int = 3) -> Reply:
        # stream-json + --verbose: one JSON event per line (assistant text, tool calls,
        # tool results, and a final "result" event)
        cmd = ["claude", "-p", prompt, "--model", self.model, "--output-format", "stream-json",
               "--verbose", "--max-turns", str(max_turns), "--tools", tools,
               "--system-prompt", self.system_prompt]
        if tools:   # read-only tools, confined to the working directory (the project snapshot)
            cmd += ["--allowedTools", tools, "--settings", confine_settings(self.workdir)]
        if schema is not None:
            cmd += ["--json-schema", json.dumps(schema.model_json_schema())]
        cmd += ["--resume", self.session_id] if self.started else ["--session-id", self.session_id]

        log, step = self.log.section, self.step
        log(f"LLM CALL [{step}] model={self.model} tools={tools or 'none'} "
            f"structured={schema.__name__ if schema is not None else 'no'} | PROMPT", prompt)
        call = len(self.calls) + 1
        began, cost_before = time.time(), self.cost_usd
        self.trace.write("llm_call", call=call, step=step, model=self.model, tools=tools, max_turns=max_turns,
                         schema=schema.__name__ if schema is not None else None, prompt=prompt)
        proc = subprocess.Popen(cmd, cwd=self.workdir, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, errors="replace")
        self.started = True
        result = None
        assert proc.stdout is not None
        for line in proc.stdout:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("type") == "result":
                result = event
            elif event.get("type") in ("assistant", "user"):
                self.trace.write("llm_event", call=call, step=step, event=event)
                self._log_event(event)
        stderr = proc.stderr.read() if proc.stderr else ""
        proc.wait()
        summary: dict = {"call": call, "step": step, "seconds": round(time.time() - began, 1), "turns": 0,
                         "tokens": {}, "cost_usd": 0.0, "outcome": f"cli failed (rc={proc.returncode})"}
        self.calls.append(summary)
        if result is None:
            log(f"[{step}] CLI FAILED rc={proc.returncode}", stderr[-2000:])
            self.trace.write("llm_result", **summary, stderr=stderr[-2000:])
            raise ClaudeCLIError(f"claude CLI failed (rc={proc.returncode}): {stderr[-500:]}")

        structured = result.get("structured_output")
        self.cost_usd = float(result.get("total_cost_usd") or self.cost_usd)
        log(f"[{step}] CALL DONE subtype={result.get('subtype')} turns={result.get('num_turns')} "
            f"session_cost=${self.cost_usd:.4f}",
            structured if structured is not None else str(result.get("result") or ""))
        usage = result.get("usage") or {}
        for key, field in [("input", "input_tokens"), ("output", "output_tokens"),
                           ("cache_write", "cache_creation_input_tokens"),
                           ("cache_read", "cache_read_input_tokens")]:
            self.tokens[key] += usage.get(field, 0)
            summary["tokens"][key] = usage.get(field, 0)
        # the CLI's cost is the session's running total: this call's share is the increase
        summary.update(turns=int(result.get("num_turns") or 1), cost_usd=round(self.cost_usd - cost_before, 6),
                       outcome=str(result.get("subtype")))
        self.trace.write("llm_result", **summary, session_cost_usd=self.cost_usd, result=result)

        if result.get("subtype") == "error_max_turns":
            raise ClaudeCLIError(f"reached the per-call turn limit ({max_turns})")
        if result.get("is_error"):
            raise ClaudeCLIError(f"claude CLI error ({result.get('subtype')}): {str(result.get('result'))[:500]}")
        if schema is not None and structured is None:
            raise ClaudeCLIError("no structured output returned")
        return Reply(text=str(result.get("result") or ""),
                     parsed=schema(**structured) if schema is not None else None,
                     turns=int(result.get("num_turns") or 1))

    def _log_event(self, event: dict) -> None:
        log, step = self.log.section, self.step
        content = event.get("message", {}).get("content", [])
        if not isinstance(content, list):
            content = [{"type": "text", "text": str(content)}]
        for block in content:
            kind = block.get("type")
            if kind == "text" and block.get("text", "").strip():
                log(f"[{step}] {'MODEL TEXT' if event['type'] == 'assistant' else 'CLI MESSAGE'}", block["text"])
            elif kind == "thinking" and block.get("thinking", "").strip():
                log(f"[{step}] MODEL THINKING", block["thinking"])
            elif kind == "tool_use":
                log(f"[{step}] TOOL CALL {block.get('name')}", block.get("input"))
            elif kind == "tool_result":
                res = block.get("content")
                if isinstance(res, list):
                    res = "\n".join(str(r.get("text", r)) if isinstance(r, dict) else str(r) for r in res)
                log(f"[{step}] TOOL RESULT", str(res))
