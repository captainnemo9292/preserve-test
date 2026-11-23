"""Fetch bug reports for Poracle's benchmark bugs from the URLs in the latest Defects4J.

Usage: python scripts/fetch_reports.py [--force]
"""

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from preserve.bugs import benchmark_bugs, fetch_report, report_url  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="re-fetch reports that are already cached")
    args = ap.parse_args()

    fetched, no_report, failed = [], [], []
    for bug in benchmark_bugs():
        if report_url(bug) is None:
            no_report.append(bug.name)
            continue
        try:
            fetch_report(bug, force=args.force)
            fetched.append(bug.name)
        except Exception as e:  # network or API error: report it, keep going
            failed.append(f"{bug.name}: {e}")
        time.sleep(0.5)

    print(f"benchmark bugs: {len(fetched) + len(no_report) + len(failed)}")
    print(f"reports fetched: {len(fetched)}")
    print(f"no report in Defects4J ({len(no_report)}): {' '.join(no_report)}")
    if failed:
        print(f"failed ({len(failed)}):")
        print("\n".join("  " + f for f in failed))


if __name__ == "__main__":
    main()
