"""Claude CLI PreToolUse hook: the model's Read, Grep and Glob calls stay inside one directory.

    python guard.py <root>      (the tool call arrives as JSON on stdin)

The working directory of `claude -p` is not a sandbox: without this hook the model can read
any file on the host, e.g. another bug's snapshot, which holds this bug's trigger tests (and
fix) when it is a later version of the same project. A call whose path resolves outside
<root> (symlinks followed), or a Glob pattern that could leave it, is blocked: exit code 2,
and the reason on stderr goes back to the model.
"""

import json
import os
import sys


def outside(root: str, path: str) -> bool:
    if path.startswith("~"):
        return True
    real = os.path.realpath(os.path.join(root, path))      # relative paths resolve against root
    return real != root and not real.startswith(root + os.sep)


def blocked(root: str, call: dict) -> list[str]:
    args = call.get("tool_input") or {}
    bad = [f"{key}={args[key]}" for key in ("file_path", "path")
           if isinstance(args.get(key), str) and args[key] and outside(root, args[key])]
    pattern = args.get("pattern")
    if call.get("tool_name") == "Glob" and isinstance(pattern, str) and (
            os.path.isabs(pattern) or pattern.startswith("~") or ".." in pattern.split("/")):
        bad.append(f"pattern={pattern}")
    return bad


def main() -> None:
    root = os.path.realpath(sys.argv[1])
    bad = blocked(root, json.load(sys.stdin))
    if bad:
        print(f"Blocked: only files under {root} may be read ({', '.join(bad)}).", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
