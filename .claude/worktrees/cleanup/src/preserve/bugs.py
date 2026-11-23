"""Benchmark bugs and their bug reports.

The benchmark is Poracle's: every Defects4J bug that has a generalized test in
Poracle's `deltas/`. A bug is usable only if the latest Defects4J lists a report
for it and the report text has been fetched (`fetch_report`).
"""

import csv
import json
import re
import urllib.request
from dataclasses import dataclass
from functools import lru_cache

from preserve.config import D4J_TABLES, PORACLE_HOST, PROJECTS, REPORTS_DIR


@dataclass(frozen=True)
class Bug:
    project: str
    bug_id: str

    @property
    def name(self) -> str:
        return f"{self.project}-{self.bug_id}"

    @classmethod
    def parse(cls, name: str) -> "Bug":
        project, bug_id = name.split("-")
        if project not in PROJECTS:
            raise ValueError(f"unsupported project: {project}")
        return cls(project, bug_id)


def benchmark_bugs() -> list[Bug]:
    """Every bug with a Poracle delta directory (Poracle's 78 bugs)."""
    bugs = []
    for project in PROJECTS:
        for d in (PORACLE_HOST / "deltas" / project).glob(f"{project}_bug*"):
            bugs.append(Bug(project, d.name.split("_bug")[1]))
    return sorted(bugs, key=lambda b: (b.project, int(b.bug_id)))


@lru_cache(maxsize=None)
def _report_urls(project: str) -> dict[str, str]:
    with open(D4J_TABLES / f"{project}-active-bugs.csv") as f:
        return {r["bug.id"]: r["report.url"] for r in csv.DictReader(f)}


def report_url(bug: Bug) -> str | None:
    url = _report_urls(bug.project).get(bug.bug_id)
    return url if url and url not in ("UNKNOWN", "NA") else None


def usable_bugs() -> list[Bug]:
    """Benchmark bugs whose report text has been fetched."""
    return [b for b in benchmark_bugs() if (REPORTS_DIR / f"{b.name}.json").exists()]


def load_report(bug: Bug) -> dict:
    return json.loads((REPORTS_DIR / f"{bug.name}.json").read_text())


def report_text(bug: Bug) -> str:
    r = load_report(bug)
    return f"Title: {r['title']}\n\n{r['body']}".strip()


# --- fetching -----------------------------------------------------------------

def _get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "preserve-testing", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _fetch(url: str) -> tuple[str, str]:
    """Return (title, body) for a JIRA, SourceForge or GitHub issue URL."""
    if m := re.match(r"https?://issues\.apache\.org/jira/browse/([A-Z]+-\d+)", url):
        d = _get_json(f"https://issues.apache.org/jira/rest/api/2/issue/{m[1]}?fields=summary,description")
        return d["fields"]["summary"], d["fields"]["description"] or ""
    if m := re.match(r"https?://sourceforge\.net/p/([^/]+)/([^/]+)/(\d+)", url):
        t = _get_json(f"https://sourceforge.net/rest/p/{m[1]}/{m[2]}/{m[3]}")["ticket"]
        return t["summary"], t["description"] or ""
    if m := re.match(r"https?://github\.com/([^/]+)/([^/]+)/issues/(\d+)", url):
        d = _get_json(f"https://api.github.com/repos/{m[1]}/{m[2]}/issues/{m[3]}")
        return d["title"], d["body"] or ""
    raise ValueError(f"unsupported report URL: {url}")


def fetch_report(bug: Bug, force: bool = False) -> dict | None:
    """Fetch and cache the bug report. Returns None when Defects4J lists no report."""
    out = REPORTS_DIR / f"{bug.name}.json"
    if out.exists() and not force:
        return json.loads(out.read_text())
    url = report_url(bug)
    if url is None:
        return None
    title, body = _fetch(url)
    report = {"bug": bug.name, "url": url, "title": title, "body": body}
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2))
    return report
