#!/usr/bin/env python3
"""all_tests.jsonl (data/all_tests.jsonl) 의 **초기 LLM 생성본** 을 experiments
디렉토리에 drop-in 한다. 서버에서 clone 후 agent 루프 돌리지 않고 바로 evaluate
돌릴 수 있게 하는 seed 스크립트.

사용법:
  python scripts/fill_initial_from_all_tests.py                  # 기본: experiments_v2 에 채움
  python scripts/fill_initial_from_all_tests.py --out experiments_v1
  python scripts/fill_initial_from_all_tests.py --bugs-file data/bugs15.txt
  python scripts/fill_initial_from_all_tests.py --all-tests data/all_tests.jsonl

생성 결과 (bug 당):
  <out>/generation/<bug>/
      JQF_<Class>.java      전체 harness (repro + preserve 메서드 포함)
      repro.java            동일
      attempts/001.java     동일 (초기 = 최종)
      result.json           메타 (success=True, valid=True, llm_call_count=0)
      trace.jsonl, verbose.log  빈 파일 / seed 메모
  <out>/generation/deltas/<Project>/<Project>_bug<N>/<file_path>/JQF_<Class>.java
"""
import argparse
import datetime
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent  # preserve-test 루트


def parse(src: str):
    pkg = re.search(r"package\s+([\w.]+)\s*;", src).group(1)
    cls = re.search(r"public\s+class\s+(JQF_\w+)", src).group(1)
    methods = re.findall(r"@Fuzz[^@]*?public\s+void\s+(\w+)\s*\(", src)
    repro = "testBugReproduction" if "testBugReproduction" in methods else methods[0]
    pres = [m for m in methods if m != repro]
    preserve = pres[0] if pres else repro
    orig_cls = cls[len("JQF_"):]
    failing = f"{pkg}.{orig_cls}::{preserve}"
    return pkg, cls, repro, preserve, failing


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="experiments_v2",
                    help="목표 experiment 디렉토리 (default: experiments_v2)")
    ap.add_argument("--all-tests", default="data/all_tests.jsonl",
                    help="all_tests.jsonl 경로 (default: data/all_tests.jsonl)")
    ap.add_argument("--bugs-file", default=None,
                    help="특정 bug 목록만 (default: all_tests.jsonl 전체)")
    args = ap.parse_args()

    all_tests_path = ROOT / args.all_tests if not pathlib.Path(args.all_tests).is_absolute() \
                     else pathlib.Path(args.all_tests)
    if not all_tests_path.exists():
        print(f"ERROR: {all_tests_path} 없음", file=sys.stderr); return 2

    bugs_filter = None
    if args.bugs_file:
        bf = ROOT / args.bugs_file if not pathlib.Path(args.bugs_file).is_absolute() \
             else pathlib.Path(args.bugs_file)
        bugs_filter = {b.strip().upper() for b in bf.read_text().split() if b.strip()}

    out_root = ROOT / args.out if not pathlib.Path(args.out).is_absolute() \
               else pathlib.Path(args.out)
    out_root.mkdir(parents=True, exist_ok=True)

    written = []
    skipped = []
    for ln in all_tests_path.read_text().splitlines():
        if not ln.strip():
            continue
        d = json.loads(ln)
        bug_id = d["bug_id"]
        if bugs_filter and bug_id not in bugs_filter:
            continue
        proj, num = bug_id.split("-")
        bug = f"{proj.capitalize()}-{num}"
        src = d["test_file"]
        try:
            pkg, cls, repro_m, preserve_m, failing = parse(src)
        except Exception as e:
            skipped.append((bug, str(e))); continue

        # deltas copy (evaluate 가 여기서 test 를 찾음)
        delta_root = out_root / "generation/deltas" / proj.capitalize() / f"{proj.capitalize()}_bug{num}"
        delta_file = delta_root / d["file_path"]
        delta_file.parent.mkdir(parents=True, exist_ok=True)
        delta_file.write_text(src)

        # generation/<bug>/ 메타 파일들
        gdir = out_root / "generation" / bug
        (gdir / "attempts").mkdir(parents=True, exist_ok=True)
        (gdir / f"{cls}.java").write_text(src)
        (gdir / "repro.java").write_text(src)
        (gdir / "attempts/001.java").write_text(src)
        (gdir / "trace.jsonl").write_text("")
        (gdir / "verbose.log").write_text(f"seeded from all_tests.jsonl for {bug}\n")

        result = {
            "bug": bug, "success": True, "failure_reason": "",
            "model": "all_tests.jsonl (initial seed, no agent loop)",
            "llm_call_count": 0, "cost_usd": 0.0, "tokens": {}, "seconds": 0.0,
            "started_at": datetime.datetime.now().isoformat(timespec="seconds"),
            "timing": {}, "llm_seconds": 0.0, "llm_calls": [], "test_versions": 1,
            "failing_test": failing,
            "jqf_class": f"{pkg}.{cls}",
            "hidden_tests": [],
            "repro_method": repro_m,
            "preserve_method": preserve_m,
            "issue_gist": "initial LLM-generated test seeded from all_tests.jsonl",
            "failing_inputs_count": 0,
            "derived": True, "derived_compiles": True,
            "test_file": f"{cls}.java",
            "poracle_delta": str(delta_root.resolve()),
            "valid": True, "validation": {}, "total_seconds": 0.0,
            "paired_inputs_archived": False,
        }
        (gdir / "result.json").write_text(json.dumps(result, indent=2))
        written.append(bug)

    print(f"wrote {len(written)} bugs to {out_root}")
    if written:
        print("sample:", ", ".join(written[:10]))
    if skipped:
        print(f"skipped {len(skipped)}: {skipped[:5]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
