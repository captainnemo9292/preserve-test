#!/usr/bin/env python3
"""Pass 2 전용: 이미 채워진 experiment 디렉토리의 preserve 메서드를 tier
feedback 기반으로 refine. agent Pass 1 은 돌지 않고, 기존 JQF_<Class>.java 의
preserve 부분만 수정.

사용 전제:
  - scripts/fill_initial_from_all_tests.py 혹은 agent generate 가 이미 실행되어
    experiments_v3/generation/<bug>/{JQF_<Class>.java, result.json, deltas/} 존재
  - poracle-reproduce 컨테이너 기동 중 (fuzz 실행 필요)

사용법:
  # 기본: experiments_v3, bugs15, 3 iter
  python scripts/apply_preserve_refine.py --out experiments_v3 \\
      --bugs-file data/bugs15.txt --iterations 3

  # 전체 63 bugs
  python scripts/apply_preserve_refine.py --out experiments_v3 \\
      --bugs-file data/bugs77.txt --iterations 3

결과:
  각 bug 의 generation/<bug>/<JQF_Class>.java 가 refine 된 버전으로 덮어씌워짐
  (deltas/도 동기화). result.json 에 preserve_refine_* 필드 추가.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from preserve.bugs import Bug, report_text
from preserve.config import DEFAULT_FUZZ_SECONDS, DEFAULT_MODEL, RESULTS_DIR
from preserve.container import Checkout
from preserve.llm import ClaudeCLI, Trace, VerboseLog
from preserve.preserve_refine import PreserveRefiner, PreserveTarget


SYSTEM_PROMPT = ("You are an expert Java/JQF test engineer. You refine preservation tests by "
                 "improving their logOutIf calls so divergent patched behavior becomes visible.")


def refine_one(bug_name: str, out_dir: str, iterations: int, fuzz_seconds: int, model: str,
               verbose: bool = False) -> dict:
    """Apply Pass 2 refinement to a single bug. Returns summary dict."""
    out = Path(out_dir)
    gen_dir = out / "generation" / bug_name
    result_path = gen_dir / "result.json"
    if not result_path.exists():
        return {"bug": bug_name, "status": "missing result.json"}

    try:
        result = json.loads(result_path.read_text())
    except Exception as e:
        return {"bug": bug_name, "status": f"result.json parse error: {e}"}

    jqf_class = result.get("jqf_class", "")
    preserve_method = result.get("preserve_method", "")
    delta_dir = result.get("poracle_delta", "")
    if not (jqf_class and preserve_method and delta_dir):
        return {"bug": bug_name, "status": "missing metadata"}

    # path_rel 는 delta_dir 아래에서 .java 파일 상대경로 유추
    test_file = result.get("test_file", "")  # e.g. "JQF_AbstractCategoryItemRendererTests.java"
    jqf_src_path = gen_dir / test_file
    if not jqf_src_path.exists():
        return {"bug": bug_name, "status": f"test file missing: {test_file}"}
    final_src = jqf_src_path.read_text()
    # path_rel: delta_dir 안에서의 상대경로
    delta_root = Path(delta_dir)
    candidates = list(delta_root.rglob(test_file))
    if not candidates:
        return {"bug": bug_name, "status": "test file not in deltas"}
    path_rel = str(candidates[0].relative_to(delta_root))

    bug = Bug.parse(bug_name)
    checkout = Checkout(bug, "b").prepare()

    workspace = out.parent / "workspace" / f"{bug_name}-refine"
    workspace.mkdir(parents=True, exist_ok=True)
    log = VerboseLog(workspace / "refine_verbose.log", echo=verbose)
    trace = Trace(workspace / "refine_trace.jsonl")
    llm = ClaudeCLI(model=model, workdir=str(workspace), system_prompt=SYSTEM_PROMPT,
                    log=log, trace=trace)
    llm.step = "preserve_refine"

    target = PreserveTarget(
        env=checkout.env, compile_fn=checkout.compile_tests,
        path=f"{checkout.dir}/{path_rel}", path_rel=path_rel,
        test_class=jqf_class, preserve_method=preserve_method,
        classpath=checkout.classpath, workdir=checkout.workdir,
        fuzz_seconds=fuzz_seconds,
    )
    refiner = PreserveRefiner(target, llm, log, trace,
                               max_iterations=iterations,
                               max_llm_calls=iterations + 2)

    t0 = time.time()
    try:
        info = refiner.refine(final_src)
    except Exception as e:
        return {"bug": bug_name, "status": f"refine error: {type(e).__name__}: {e}",
                "seconds": round(time.time() - t0, 1)}

    # Persist updated test
    if info["final_src"] != final_src:
        jqf_src_path.write_text(info["final_src"])
        (gen_dir / "repro.java").write_text(info["final_src"])
        (Path(delta_dir) / path_rel).write_text(info["final_src"])
        changed = True
    else:
        changed = False

    result["preserve_refine_iterations"] = info["iterations_run"]
    result["preserve_refine_llm_calls"] = info["llm_calls"]
    result["preserve_refine_features"] = info["features_history"]
    result["preserve_refine_cost_usd"] = llm.cost_usd
    result["preserve_refine_seconds"] = round(time.time() - t0, 1)
    result["preserve_refine_changed"] = changed
    result_path.write_text(json.dumps(result, indent=2, ensure_ascii=False))

    return {
        "bug": bug_name, "status": "ok",
        "iterations": info["iterations_run"], "llm_calls": info["llm_calls"],
        "cost_usd": round(llm.cost_usd, 4),
        "seconds": round(time.time() - t0, 1),
        "changed": changed,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="experiments_v3", help="experiment 디렉토리")
    ap.add_argument("--bugs-file", default="data/bugs15.txt", help="bug 리스트 파일")
    ap.add_argument("--iterations", type=int, default=3)
    ap.add_argument("--fuzz-seconds", type=int, default=DEFAULT_FUZZ_SECONDS)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("-j", "--jobs", type=int, default=1)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    bugs_file = Path(args.bugs_file)
    if not bugs_file.is_absolute():
        bugs_file = Path(__file__).resolve().parent.parent / bugs_file
    bugs = [b.strip() for b in bugs_file.read_text().split() if b.strip()]
    print(f"refining {len(bugs)} bugs in {args.out} (iterations={args.iterations}, "
          f"fuzz_seconds={args.fuzz_seconds}, jobs={args.jobs})")

    rows = []
    if args.jobs <= 1:
        for b in bugs:
            print(f"[{b}] ...", flush=True)
            row = refine_one(b, args.out, args.iterations, args.fuzz_seconds, args.model, args.verbose)
            rows.append(row)
            print(f"  → {row}")
    else:
        with ProcessPoolExecutor(max_workers=args.jobs) as pool:
            futs = {pool.submit(refine_one, b, args.out, args.iterations, args.fuzz_seconds,
                                args.model, args.verbose): b for b in bugs}
            for f in as_completed(futs):
                rows.append(f.result())
                print(f"  → {rows[-1]}", flush=True)

    # summary
    ok = [r for r in rows if r.get("status") == "ok"]
    print()
    print(f"=== summary ===")
    print(f"ok: {len(ok)}/{len(rows)}")
    print(f"changed: {sum(1 for r in ok if r.get('changed'))}")
    print(f"total cost: ${sum(r.get('cost_usd', 0) for r in ok):.2f}")
    print(f"total time: {sum(r.get('seconds', 0) for r in ok):.0f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
