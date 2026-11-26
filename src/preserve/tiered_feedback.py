#!/usr/bin/env python3
"""Tiered feedback generator from LLMFeatureCollector features.json.

Three independent tiers for ablation study. Each tier is a pure function that
takes the features dict and returns a feedback text block (or "" when nothing
to say under that tier). Compose with `build()` or the CLI.

Does NOT modify preserve-test or any existing prompt. The output is a text
block the caller can append to its own prompt wherever appropriate.

Tiers (ranked by discriminative power from the v4 ablation on 6 bugs):

  essential      8 top-rank signals — always recommended
                 (failure_mode, signal_flags, target_hit_rate, target_hit_count,
                  branch_coverage_patch_minus_org, save_queue_why,
                  output_shape_tokens, top_arg_tuples)

  supplementary  9 numeric details — enable for fine-grained guidance
                 (precondition_fail_rate, max_invalid_streak,
                  collapsed_args_count, arg_tuple_diversity,
                  unique_output_shapes_org/patch, org_patch_shape_delta,
                  output_distance, arg_types, arg_distribution)

  bulk           raw sample outputs — high token cost, enable only when
                 literal strings/values matter (e.g. Lang-58 trigger strings)

Usage:
    from tiered_feedback import build
    txt = build(features_dict, essential=True, supplementary=True, bulk=False)

CLI:
    python tiered_feedback.py features.json                     # essential + supplementary
    python tiered_feedback.py features.json --bulk               # + raw samples
    python tiered_feedback.py features.json --only essential     # ablation: essential only
    python tiered_feedback.py features.json --no-supplementary   # ablation: drop middle tier
"""
from __future__ import annotations
import argparse
import json
import sys
from typing import Any

# ---------- helpers ----------

def _fmt_map(m: dict, top_n: int = 5) -> str:
    """Compact map rendering: 'key: count' sorted by count desc, top_n shown."""
    if not m:
        return "{}"
    items = sorted(m.items(), key=lambda kv: -kv[1])[:top_n]
    return "{" + ", ".join(f'"{k}": {v}' for k, v in items) + "}"


def _flags_on(flags: dict) -> list[str]:
    return [k for k, v in (flags or {}).items() if v]


# ---------- Tier 1: essential ----------

def essential(f: dict) -> str:
    """Top 8 signals by discriminative power. Short, actionable, always safe."""
    lines: list[str] = ["## FUZZER FEEDBACK (essential)"]

    # failure_mode + signal_flags
    fm = f.get("failure_mode", "?")
    flags_on = _flags_on(f.get("signal_flags", {}))
    lines.append(f"failure_mode: {fm}")
    if flags_on:
        lines.append(f"signal_flags fired: {', '.join(flags_on)}")

    # target_hit — the single most decisive signal: did the test ever reach
    # the patched method?
    thc = f.get("target_hit_count", 0)
    thr = f.get("target_hit_rate", 0.0)
    fth = f.get("first_target_hit_iteration", -1)
    vi = f.get("valid_iterations", 0)
    if vi == 0:
        lines.append("target_hit: n/a — no valid iterations ran")
    elif thc == 0:
        lines.append(f"target_hit: 0/{vi} valid iterations reached the patched method — "
                     "the test calls the API but control flow never enters the patched branch. "
                     "Pick a different API entry point or widen the argument domain to cover the patched code path.")
    elif thr < 0.1:
        lines.append(f"target_hit: {thc}/{vi} ({thr*100:.1f}%) — rarely hits the patched method. "
                     "Narrow @InRange toward inputs that are known to activate the branch.")
    else:
        lines.append(f"target_hit: {thc}/{vi} ({thr*100:.0f}%)"
                     + (f", first at iteration {fth}" if fth >= 0 else ""))

    # branch_coverage patch - org
    bcd = f.get("branch_coverage_patch_minus_org", None)
    if bcd is not None and bcd != -1:
        if bcd > 0:
            lines.append(f"branch_coverage_patch_minus_org: +{bcd} — the patched version exposes "
                         f"{bcd} branches the buggy one does not. The test does explore divergent territory.")
        elif bcd == 0:
            lines.append("branch_coverage_patch_minus_org: 0 — buggy and patched cover the same branches. "
                         "Likely the test never triggers divergent control flow.")
        else:
            lines.append(f"branch_coverage_patch_minus_org: {bcd} — buggy covers MORE branches than patched. "
                         "The test explores buggy-only edge cases but not the patch region.")

    # save_queue_why — Zest heuristic activity summary
    sqw = f.get("save_queue_why", {})
    if sqw:
        total = sum(sqw.values())
        heuristics = set()
        for k in sqw:
            for tok in k.split("+"):
                if tok: heuristics.add(tok)
        lines.append(f"save_queue_why: {total} saves across heuristics {sorted(heuristics)}. "
                     f"Top: {_fmt_map(sqw, 3)}")
        if len(heuristics) <= 1:
            lines.append("  → only one heuristic is guiding Zest — the save queue cannot diversify.")

    # output_shape_tokens — type templates
    ost = f.get("output_shape_tokens", {})
    if ost:
        lines.append(f"output_shape_tokens: {len(ost)} distinct template(s). Top: {_fmt_map(ost, 5)}")
        if len(ost) == 1:
            lines.append("  → every patched output has the same structure; the harness does not "
                         "differentiate inputs enough. Add diverse branches or enum trigger values.")

    # top_arg_tuples — which specific inputs dominate
    tat = f.get("top_arg_tuples", {})
    if tat:
        total_in_top = sum(tat.values())
        vi_safe = max(vi, 1)
        top_share = (max(tat.values()) / vi_safe) if tat else 0.0
        lines.append(f"top_arg_tuples (top 5): {_fmt_map(tat, 5)}")
        if top_share >= 0.3:
            lines.append(f"  → {top_share*100:.0f}% of valid iterations collapsed onto one tuple. "
                         "Zest bias is stuck — widen @InRange or add isFixed=true.")

    return "\n".join(lines) if len(lines) > 1 else ""


# ---------- Tier 2: supplementary ----------

def supplementary(f: dict) -> str:
    """Numeric details for finer guidance. Only emits when values are informative."""
    lines: list[str] = ["## FUZZER FEEDBACK (supplementary)"]

    # precondition
    pfr = f.get("precondition_fail_rate", 0)
    mis = f.get("max_invalid_streak", 0)
    it = f.get("iterations", 0)
    if pfr > 0:
        lines.append(f"precondition_fail_rate: {pfr*100:.1f}% of {it} inputs rejected by assumeTrue "
                     f"(max consecutive invalid streak: {mis}).")
        if pfr > 0.5:
            lines.append("  → severe queue starvation; narrow @InRange or clip inputs inside the test.")
        elif mis > 100:
            lines.append("  → scattered fails but a long starvation burst occurred mid-run.")

    # arg-space
    cac = f.get("collapsed_args_count", 0)
    atd = f.get("arg_tuple_diversity", None)
    adist = f.get("arg_distribution", {})
    if adist:
        if cac > 0:
            lines.append(f"collapsed_args_count: {cac}/{len(adist)} int args produced only ONE value. "
                         "Bias collapsed on those positions; consider widening or removing @InRange.")
        if atd is not None and atd < 0.3:
            lines.append(f"arg_tuple_diversity: {atd:.2f} — the same tuples are being re-tried; "
                         "save queue cycles on few seeds.")
    at = f.get("arg_types", {})
    if at:
        lines.append(f"arg_types: {at}")

    # output diversity
    uso = f.get("unique_output_shapes_org", 0)
    usp = f.get("unique_output_shapes_patch", 0)
    opd = f.get("org_patch_shape_delta", None)
    if uso or usp:
        lines.append(f"unique_output_shapes: org={uso}, patch={usp}, symmetric_delta={opd}")
        if opd == 0 and usp > 10:
            lines.append("  → wide output diversity but org and patch sets are identical. "
                         "The test never produces a divergent output on any input tried.")

    # output distance (near-miss magnitude)
    od = f.get("output_distance", {})
    if od and (od.get("max", 0) > 0 or od.get("sum", 0) > 0):
        hist = od.get("histogram", {})
        lines.append(f"output_distance: max={od.get('max',0)}, min_positive={od.get('min_positive',-1)}, "
                     f"histogram={hist}")
        if hist.get("1-10", 0) > 0 or hist.get("11-100", 0) > 0:
            lines.append("  → some inputs produced near-miss outputs (small byte-distance); "
                         "widen @InRange around the observed region or add boundary enumeration.")

    # arg_distribution brief (don't dump full map, show ranges)
    if adist:
        ranges = ", ".join(f"{k}=[{v.get('min_seen','?')}..{v.get('max_seen','?')}]"
                           for k, v in adist.items())
        lines.append(f"arg_distribution: {ranges}")

    return "\n".join(lines) if len(lines) > 1 else ""


# ---------- Tier 3: bulk ----------

def bulk(f: dict) -> str:
    """Raw sampled outputs — high token cost. Enable only when literal values matter."""
    lines: list[str] = ["## FUZZER FEEDBACK (bulk — raw samples)"]

    spo = f.get("sample_patch_outputs", {})
    soo = f.get("sample_org_outputs", {})
    # emit only if they differ (both-identical case has no new info)
    if spo:
        top = sorted(spo.items(), key=lambda kv: -kv[1])[:10]
        lines.append("sample_patch_outputs (top 10 by frequency):")
        for k, v in top:
            disp = k if len(k) <= 160 else k[:157] + "..."
            lines.append(f"  [{v:>4}×] {disp}")
    if soo and soo != spo:
        top = sorted(soo.items(), key=lambda kv: -kv[1])[:5]
        lines.append("sample_org_outputs (top 5, differ from patch):")
        for k, v in top:
            disp = k if len(k) <= 160 else k[:157] + "..."
            lines.append(f"  [{v:>4}×] {disp}")
    elif soo and soo == spo:
        lines.append("sample_org_outputs == sample_patch_outputs (omitted)")

    return "\n".join(lines) if len(lines) > 1 else ""


# ---------- composition ----------

def build(features: dict, essential_on: bool = True,
          supplementary_on: bool = True, bulk_on: bool = False) -> str:
    """Compose selected tiers into one feedback text block."""
    blocks = []
    if essential_on:
        b = essential(features)
        if b: blocks.append(b)
    if supplementary_on:
        b = supplementary(features)
        if b: blocks.append(b)
    if bulk_on:
        b = bulk(features)
        if b: blocks.append(b)
    return "\n\n".join(blocks)


# ---------- CLI ----------

def _main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Tiered feedback from features.json")
    ap.add_argument("features_json", help="Path to llm_features.json")
    ap.add_argument("--no-essential", action="store_true")
    ap.add_argument("--no-supplementary", action="store_true")
    ap.add_argument("--bulk", action="store_true", help="Include raw sample outputs (high token cost)")
    ap.add_argument("--only", choices=["essential", "supplementary", "bulk"],
                    help="Shortcut: emit ONLY this tier (overrides the other flags)")
    ap.add_argument("--chars", action="store_true", help="Print byte/char count of each tier")
    args = ap.parse_args(argv)

    with open(args.features_json) as fh:
        features = json.load(fh)

    if args.only:
        text = build(features,
                     essential_on=(args.only == "essential"),
                     supplementary_on=(args.only == "supplementary"),
                     bulk_on=(args.only == "bulk"))
    else:
        text = build(features,
                     essential_on=not args.no_essential,
                     supplementary_on=not args.no_supplementary,
                     bulk_on=args.bulk)

    if args.chars:
        e_txt = essential(features)
        s_txt = supplementary(features)
        b_txt = bulk(features)
        print(f"[chars] essential={len(e_txt)} supplementary={len(s_txt)} bulk={len(b_txt)}", file=sys.stderr)

    sys.stdout.write(text)
    if not text.endswith("\n"):
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
