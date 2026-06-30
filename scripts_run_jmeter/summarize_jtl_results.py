#!/usr/bin/env python3
r"""
Summarize JMeter JTL CSV files without external dependencies.

Usage:
  python scripts_run_jmeter/summarize_jtl_results.py D:\path\to\ALB_Test\results\02_medium_dependency_slowdown_mixed_0600_tst
  python scripts_run_jmeter/summarize_jtl_results.py . --output summary.csv

Expected JMeter columns: timeStamp, elapsed, success. The script tolerates extra columns.
"""
from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path
from statistics import mean, pstdev


def percentile(values: list[float], q: float) -> float:
    if not values:
        return float("nan")
    xs = sorted(values)
    if len(xs) == 1:
        return xs[0]
    pos = (len(xs) - 1) * q
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return xs[lo]
    return xs[lo] + (xs[hi] - xs[lo]) * (pos - lo)


def summarize_file(path: Path) -> dict[str, float | int | str]:
    elapsed: list[float] = []
    timestamps: list[int] = []
    errors = 0
    total = 0

    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                e = float(row.get("elapsed", ""))
            except ValueError:
                continue
            elapsed.append(e)
            total += 1

            success = str(row.get("success", "true")).strip().lower()
            if success not in {"true", "1", "yes"}:
                errors += 1

            try:
                timestamps.append(int(float(row.get("timeStamp", "0"))))
            except ValueError:
                pass

    if timestamps:
        duration_s = max((max(timestamps) - min(timestamps)) / 1000.0, 1e-9)
        throughput = total / duration_s
    else:
        duration_s = float("nan")
        throughput = float("nan")

    return {
        "file": str(path),
        "strategy_folder": path.parent.name,
        "run": path.stem,
        "samples": total,
        "errors": errors,
        "error_rate_percent": (errors / total * 100.0) if total else float("nan"),
        "throughput_rps": throughput,
        "duration_s": duration_s,
        "avg_ms": mean(elapsed) if elapsed else float("nan"),
        "p50_ms": percentile(elapsed, 0.50),
        "p90_ms": percentile(elapsed, 0.90),
        "p95_ms": percentile(elapsed, 0.95),
        "p99_ms": percentile(elapsed, 0.99),
        "max_ms": max(elapsed) if elapsed else float("nan"),
    }


def aggregate(rows: list[dict[str, float | int | str]]) -> list[dict[str, float | int | str]]:
    groups: dict[str, list[dict[str, float | int | str]]] = {}
    for r in rows:
        groups.setdefault(str(r["strategy_folder"]), []).append(r)

    out: list[dict[str, float | int | str]] = []
    for group, rs in sorted(groups.items()):
        metrics = ["avg_ms", "p50_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms", "throughput_rps", "error_rate_percent"]
        item: dict[str, float | int | str] = {
            "file": "AGGREGATE",
            "strategy_folder": group,
            "run": "mean_of_runs",
            "samples": sum(int(r["samples"]) for r in rs),
            "errors": sum(int(r["errors"]) for r in rs),
            "duration_s": sum(float(r["duration_s"]) for r in rs if not math.isnan(float(r["duration_s"]))),
        }
        for m in metrics:
            vals = [float(r[m]) for r in rs if not math.isnan(float(r[m]))]
            item[m] = mean(vals) if vals else float("nan")
            item[f"{m}_std"] = pstdev(vals) if len(vals) > 1 else 0.0
        out.append(item)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="Result root containing .jtl files")
    parser.add_argument("--output", type=Path, default=None, help="Output CSV path")
    args = parser.parse_args()

    files = sorted(args.root.rglob("*.jtl"))
    if not files:
        print(f"No .jtl files found under {args.root}")
        return 1

    rows = [summarize_file(p) for p in files]
    rows_with_agg = rows + aggregate(rows)

    fields: list[str] = []
    for r in rows_with_agg:
        for k in r.keys():
            if k not in fields:
                fields.append(k)

    out_path = args.output or (args.root / "jtl-summary.csv")
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows_with_agg)

    print(f"Wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
