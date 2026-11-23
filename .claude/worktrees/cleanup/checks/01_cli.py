"""Claude CLI backend: structured output, a tool-using call, and session memory.

Run: python checks/01_cli.py [model]   (default haiku; costs a few cents)
"""

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from preserve.agent import GeneralizeOutput, TestOutput  # noqa: E402
from preserve.llm import READ_ONLY_TOOLS, ClaudeCLI, VerboseLog  # noqa: E402

model = sys.argv[1] if len(sys.argv) > 1 else "haiku"
project = Path(tempfile.mkdtemp(prefix="cli_check_"))
(project / "Adder.java").write_text(
    "public class Adder {\n  public static int add(int a, int b) { return a - b; } // BUG\n}\n")
llm = ClaudeCLI(model, str(project), "You are an expert software engineer.", VerboseLog(None))

# 1. structured output
out = llm.ask("Analyze this bug report: Adder.add(2, 3) returns -1 instead of 5.", GeneralizeOutput).parsed
print("issue_gist:", out.issue_gist, "| examples:", len(out.concrete_examples))
assert out.issue_gist and out.concrete_examples

# 2. a call with read-only tools on the project directory
reply = llm.ask("Read Adder.java in the current directory with your tools. Return pbt = the exact "
                "line of code that contains the bug.", TestOutput, tools=READ_ONLY_TOOLS, max_turns=10)
print("found:", reply.parsed.pbt, "| turns:", reply.turns)
assert "a - b" in reply.parsed.pbt

# 3. the session keeps the conversation
answer = llm.ask("In one word: which method did the bug report mention?").text
print("memory:", answer)
assert "add" in answer.lower()

print(f"cost ${llm.cost_usd:.4f}, tokens {llm.tokens}\nCLI checks passed")
