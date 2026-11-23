"""Step 2: derive the preservation test from the bug-reproducing test.

The repro method must have exactly this top-level shape (checked by `format_problems`):

    @Fuzz
    public void testBugReproduction(<every parameter has @InRange or @From>) {
        boolean validIf = <expr>;
        boolean buggyIf = <expr>;
        assumeTrue(validIf);
        assumeTrue(buggyIf);
        <statements>
        Log.logOutIf(true, () -> new T[]{ ... });
        <local variable declarations and assertions only; at least one assertion>
    }

The code after the log line serves only the assertions (e.g. it computes the expected value),
so the derived method leaves all of it out.

`derive` turns it into Poracle's preservation test by plain text edits:

    @Fuzz
    public void <test>(<same parameters>) {
        boolean validIf = <expr>;
        boolean buggyIf = <expr>;
        boolean preserveIf = !buggyIf;
        assumeTrue(validIf);
        assumeTrue(preserveIf);
        try {
            <statements>
            Log.logOutIf(preserveIf, () -> new T[]{ ... });
        } catch (TimeoutException) { rethrow } catch (Exception e) { log e.toString() }
    }
"""

import re
from dataclasses import dataclass


# --- a small Java scanner ---------------------------------------------------------

def _skip_literal_or_comment(src: str, i: int) -> int:
    """If src[i] starts a string/char literal or a comment, return the index after it, else i."""
    if src.startswith("//", i):
        j = src.find("\n", i)
        return len(src) if j < 0 else j
    if src.startswith("/*", i):
        j = src.find("*/", i + 2)
        return len(src) if j < 0 else j + 2
    if src[i] in "\"'":
        q, j = src[i], i + 1
        while j < len(src) and src[j] != q:
            j += 2 if src[j] == "\\" else 1
        return j + 1
    return i


def _match(src: str, open_idx: int) -> int:
    """Index of the bracket closing the one at open_idx."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    stack, i = [], open_idx
    while i < len(src):
        j = _skip_literal_or_comment(src, i)
        if j != i:
            i = j
            continue
        c = src[i]
        if c in pairs:
            stack.append(pairs[c])
        elif c in ")}]":
            if not stack or stack.pop() != c:
                raise ValueError(f"unbalanced '{c}' at offset {i}")
            if not stack:
                return i
        i += 1
    raise ValueError("unclosed bracket")


def _strip_comments(text: str) -> str:
    out, i = [], 0
    while i < len(text):
        if text.startswith(("//", "/*"), i):
            i = _skip_literal_or_comment(text, i)
            continue
        j = _skip_literal_or_comment(text, i)
        out.append(text[i:j] if j != i else text[i])
        i = j if j != i else i + 1
    return "".join(out)


@dataclass
class Stmt:
    start: int          # offsets into the method body text
    end: int

    def text(self, body: str) -> str:
        return body[self.start:self.end]

    def code(self, body: str) -> str:
        """Statement without comments, whitespace collapsed."""
        return re.sub(r"\s+", " ", _strip_comments(self.text(body))).strip()


def _statements(body: str) -> list[Stmt]:
    """Top-level statements of a method body (text between the braces)."""
    stmts, i, start = [], 0, None
    expr = False        # the statement is an expression so far: a `{` in it is not a block
    while i < len(body):
        j = _skip_literal_or_comment(body, i)
        if j != i:
            i = j
            continue
        c = body[i]
        if c.isspace():
            i += 1
            continue
        if start is None:
            start = i
            expr = bool(re.match(r"(return|throw)\b", body[i:]))
        # brackets are skipped whole, so what is seen here is at the statement's top level:
        # `=` (not ==, <=, >=, !=) or `->` makes the rest an expression, whose `{` opens an
        # array initializer, a lambda body or an anonymous class, and the `;` ends it
        if (c == "=" and body[i + 1:i + 2] != "=" and body[i - 1:i] not in ("=", "!", "<", ">")) \
                or body.startswith("->", i):
            expr = True
        if c in "({[":
            i = _match(body, i) + 1
            if c == "{" and not expr:   # a block ends the statement unless catch/finally/else follows
                rest = _strip_comments(body[i:]).lstrip()
                if not re.match(r"(catch|finally|else)\b", rest):
                    stmts.append(Stmt(start, i))
                    start = None
            continue
        if c == ";":
            stmts.append(Stmt(start, i + 1))
            start = None
        i += 1
    if start is not None and body[start:].strip():
        stmts.append(Stmt(start, len(body)))
    return stmts


# --- locating the method ------------------------------------------------------------

@dataclass
class Method:
    header_start: int   # start of annotations (e.g. @Fuzz)
    params_open: int
    params_close: int
    body_open: int
    body_close: int

    def body(self, src: str) -> str:
        return src[self.body_open + 1:self.body_close]


def find_method(src: str, name: str) -> Method:
    m = re.search(rf"\bvoid\s+{re.escape(name)}\s*\(", src)
    if not m:
        raise ValueError(f"method {name} not found")
    params_open = m.end() - 1
    params_close = _match(src, params_open)
    body_open = src.index("{", params_close)
    body_close = _match(src, body_open)
    # header: back to the end of the previous member/statement
    prev = max(src.rfind(c, 0, m.start()) for c in ";{}")
    header_start = prev + 1
    while header_start < m.start() and src[header_start] in " \t\r\n":
        header_start += 1
    # include the indentation of the header line
    line_start = src.rfind("\n", 0, header_start) + 1
    return Method(line_start, params_open, params_close, body_open, body_close)


def _split_params(params: str) -> list[str]:
    parts, depth, cur, i = [], 0, [], 0
    while i < len(params):
        j = _skip_literal_or_comment(params, i)
        if j != i:
            cur.append(params[i:j])
            i = j
            continue
        c = params[i]
        depth += c in "(<[{"
        depth -= c in ")>]}"
        if c == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


# --- format check -----------------------------------------------------------------

_VALID = re.compile(r"^boolean validIf = .+;$")
_BUGGY = re.compile(r"^boolean buggyIf = .+;$")
_ASSUME_VALID = re.compile(r"^(?:Assume\.)?assumeTrue\(\s*validIf\s*\);$")
_ASSUME_BUGGY = re.compile(r"^(?:Assume\.)?assumeTrue\(\s*buggyIf\s*\);$")
_LOG = re.compile(r"^(?:anonymous\.log\.)?Log\.logOutIf\(\s*true\s*,")
_ASSERT = re.compile(r"^(?:(?:org\.junit\.)?Assert\.)?(?:assert\w*|fail)\s*\(")
# a local variable declaration with an initializer: [final] Type[<...>][[]] name[[]] = ...;
_DECL = re.compile(r"^(?:final\s+)?(?P<type>[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*(?:\s*<.*>)?(?:\s*\[\s*\])*)"
                   r"\s+[A-Za-z_$][\w$]*(?:\s*\[\s*\])*\s*=(?!=).+;$")
_KEYWORDS = {"return", "throw", "new", "assert", "if", "else", "for", "while", "do", "switch", "case", "break",
             "continue", "try", "catch", "finally", "synchronized", "yield"}


def _is_local_declaration(code: str) -> bool:
    m = _DECL.match(code)
    return bool(m) and m.group("type").split("<")[0].strip() not in _KEYWORDS


@dataclass
class Parsed:
    method: Method
    body: str
    stmts: list[Stmt]
    log_idx: int


def parse_repro(src: str, name: str) -> Parsed:
    """Locate the repro method and its parts; raise ValueError with a readable reason."""
    method = find_method(src, name)
    body = method.body(src)
    stmts = _statements(body)
    codes = [s.code(body) for s in stmts]
    if len(codes) < 6:
        raise ValueError("the method body is too short for the required structure")
    expected = [(_VALID, "boolean validIf = <expression>;"),
                (_BUGGY, "boolean buggyIf = <expression>;"),
                (_ASSUME_VALID, "assumeTrue(validIf);"),
                (_ASSUME_BUGGY, "assumeTrue(buggyIf);")]
    for k, (pattern, want) in enumerate(expected):
        if not pattern.match(codes[k]):
            raise ValueError(f"statement {k + 1} of the method must be `{want}`, found `{codes[k][:120]}`")
    logs = [k for k, c in enumerate(codes) if _LOG.match(c)]
    nested_logs = len(re.findall(r"Log\.logOutIf\s*\(", _strip_comments(body))) - len(logs)
    if len(logs) != 1 or nested_logs:
        raise ValueError("the method must contain exactly one `Log.logOutIf(true, () -> new T[]{ ... });` "
                         "as a top-level statement (not inside a block)")
    log_idx = logs[0]
    if log_idx < 4:
        raise ValueError("`Log.logOutIf(true, ...)` must come after the two assumeTrue statements")
    after = codes[log_idx + 1:]
    for c in after:
        if not _ASSERT.match(c) and not _is_local_declaration(c):
            raise ValueError("only local variable declarations and assertions may follow the "
                             f"`Log.logOutIf(true, ...)` line; found `{c[:120]}`")
    if not any(_ASSERT.match(c) for c in after):
        raise ValueError("the method must end with assertions after the `Log.logOutIf(true, ...)` line")
    for c in codes[4:log_idx]:
        if re.search(r"\b(?:validIf|buggyIf)\s*=", c):
            raise ValueError("validIf and buggyIf must not be reassigned")
    return Parsed(method, body, stmts, log_idx)


def format_problems(src: str, name: str) -> list[str]:
    """Problems that would stop `derive` (empty list = derivable)."""
    problems = []
    try:
        p = parse_repro(src, name)
    except ValueError as e:
        return [str(e)]
    header = src[p.method.header_start:p.method.params_open]
    if "@Fuzz" not in header:
        problems.append(f"method {name} must be annotated with @Fuzz")
    params = _split_params(src[p.method.params_open + 1:p.method.params_close])
    if not params:
        problems.append(f"method {name} must take at least one parameter")
    for prm in params:
        if "@InRange" not in prm and "@From" not in prm and not re.match(r"(final\s+)?(boolean|Boolean)\s", prm):
            problems.append(f"parameter `{prm}` needs @InRange(...) or @From(...)")
    # buggyIf must depend on the inputs: a constant (e.g. `true`) leaves no valid input
    # outside the bug domain, so the derived preservation test would check nothing
    names = {re.split(r"\s+", prm.strip())[-1] for prm in params}
    buggy_expr = p.stmts[1].code(p.body).split("=", 1)[1]
    if not any(re.search(rf"\b{re.escape(n)}\b", buggy_expr) for n in names):
        problems.append("buggyIf must be an expression over the parameters that is false for the valid "
                        "inputs that do not trigger the bug; it is a constant here. Add parameters "
                        "(e.g. for the configuration or state the bug depends on) so that the test "
                        "also generates valid inputs that do NOT trigger the bug")
    if "@RunWith(JQF.class)" not in re.sub(r"\s+", "", src).replace("edu.berkeley.cs.jqf.fuzz.", ""):
        problems.append("the test class must be annotated with @RunWith(JQF.class)")
    return problems


# --- derivation -------------------------------------------------------------------

def _reindent(text: str, extra: str) -> str:
    return "\n".join((extra + l) if l.strip() else l for l in text.splitlines())


def derive(src: str, repro_name: str, preserve_name: str) -> str:
    """Return `src` with the preservation method inserted after the repro method."""
    problems = format_problems(src, repro_name)
    if problems:
        raise ValueError("; ".join(problems))
    if re.search(rf"\bvoid\s+{re.escape(preserve_name)}\s*\(", src):
        raise ValueError(f"method {preserve_name} already exists")
    p = parse_repro(src, repro_name)
    body, st = p.body, p.stmts
    m = re.search(r"\n([ \t]*)\S", body)
    ind = m.group(1) if m else "        "

    valid_txt = st[0].text(body).strip()
    buggy_txt = st[1].text(body).strip()
    # code between `assumeTrue(buggyIf);` and the end of the logOutIf statement, verbatim
    core_start, log_stmt = st[3].end, st[p.log_idx]
    log_text = re.sub(r"logOutIf\(\s*true\s*,", "logOutIf(preserveIf,", log_stmt.text(body), count=1)
    core = (body[core_start:log_stmt.start] + log_text).lstrip("\r\n")

    header = src[p.method.header_start:p.method.params_open]
    header = re.sub(rf"\b{re.escape(repro_name)}\s*$", preserve_name, header.rstrip())
    params = src[p.method.params_open:p.method.params_close + 1]
    member_ind = re.match(r"[ \t]*", header).group(0)

    lines = [
        f"{header}{params} {{",
        f"{ind}{valid_txt}",
        f"{ind}{buggy_txt}",
        f"{ind}boolean preserveIf = !buggyIf;",
        "",
        f"{ind}assumeTrue(validIf);",
        f"{ind}assumeTrue(preserveIf);",
        "",
        f"{ind}try {{",
        _reindent(core.rstrip(), "    "),
        f"{ind}}} catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {{",
        f"{ind}    throw __timeout;",
        f"{ind}}} catch (Exception __e) {{",
        f"{ind}    Log.logOutIf(preserveIf, () -> new String[]{{ __e.toString() }});",
        f"{ind}}}",
        f"{member_ind}}}",
    ]
    preserve = "\n".join(lines)
    insert_at = p.method.body_close + 1
    return src[:insert_at] + "\n\n" + preserve + src[insert_at:]
