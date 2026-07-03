#!/usr/bin/env python3
r"""
Summarize JMeter JTL CSV files without external dependencies.

By default, this script summarizes only real measurement samplers whose label
starts with MEASURE_. It intentionally excludes setup, teardown, DISCARD_ warmup,
DISCARD_ ramp-down, chaos control calls, and other non-measurement rows so the
reported latency/throughput values are suitable for benchmark comparison.

Usage:
  python scripts_run_jmeter/summarize_jtl_results.py D:\path\to\ALB_Test\results\02_medium_dependency_slowdown_mixed_0600_tst
  python scripts_run_jmeter/summarize_jtl_results.py . --output summary.csv
  python scripts_run_jmeter/summarize_jtl_results.py . --include-all

Expected JMeter columns: timeStamp, elapsed, success, label. The script tolerates
extra columns and common case variations in column names.
"""
from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path
from statistics import mean, pstdev

DEFAULT_LABEL_PREFIX = "MEASURE_"


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


def get_case_insensitive(row: dict[str, str], key: str, default: str = "") -> str:
    if key in row:
        return row.get(key, default) or default
    lower_key = key.lower()
    for k, v in row.items():
        if k.lower() == lower_key:
            return v or default
    return default


def should_include_row(row: dict[str, str], include_all: bool, label_prefix: str) -> bool:
    if include_all:
        return True
    label = get_case_insensitive(row, "label", "").strip()
    return label.startswith(label_prefix)


def summarize_file(path: Path, include_all: bool = False, label_prefix: str = DEFAULT_LABEL_PREFIX) -> dict[str, float | int | str]:
    elapsed: list[float] = []
    timestamps: list[int] = []
    errors = 0
    included = 0
    excluded = 0
    included_labels: set[str] = set()

    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if not should_include_row(row, include_all, label_prefix):
                excluded += 1
                continue

            try:
                e = float(get_case_insensitive(row, "elapsed", ""))
            except ValueError:
                excluded += 1
                continue

            elapsed.append(e)
            included += 1

            label = get_case_insensitive(row, "label", "").strip()
            if label:
                included_labels.add(label)

            success = str(get_case_insensitive(row, "success", "true")).strip().lower()
            if success not in {"true", "1", "yes"}:
                errors += 1

            try:
                timestamps.append(int(float(get_case_insensitive(row, "timeStamp", "0"))))
            except ValueError:
                pass

    if timestamps:
        duration_s = max((max(timestamps) - min(timestamps)) / 1000.0, 1e-9)
        throughput = included / duration_s
    else:
        duration_s = float("nan")
        throughput = float("nan")

    mode = "all_labels" if include_all else f"label_prefix:{label_prefix}"
    return {
        "file": str(path),
        "strategy_folder": path.parent.name,
        "run": path.stem,
        "filter_mode": mode,
        "included_labels": ";".join(sorted(included_labels)),
        "samples": included,
        "excluded_samples": excluded,
        "errors": errors,
        "error_rate_percent": (errors / included * 100.0) if included else float("nan"),
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
        # Skip files that had no measurement samples. This can happen when the
        # result root contains unrelated JTL files.
        if int(r.get("samples", 0)) <= 0:
            continue
        groups.setdefault(str(r["strategy_folder"]), []).append(r)

    out: list[dict[str, float | int | str]] = []
    for group, rs in sorted(groups.items()):
        metrics = ["avg_ms", "p50_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms", "throughput_rps", "error_rate_percent"]
        item: dict[str, float | int | str] = {
            "file": "AGGREGATE",
            "strategy_folder": group,
            "run": "mean_of_runs",
            "filter_mode": str(rs[0].get("filter_mode", "")),
            "included_labels": "MULTIPLE_RUNS",
            "samples": sum(int(r["samples"]) for r in rs),
            "excluded_samples": sum(int(r.get("excluded_samples", 0)) for r in rs),
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
    parser.add_argument(
        "--label-prefix",
        default=DEFAULT_LABEL_PREFIX,
        help="Only include sampler labels that start with this prefix. Default: MEASURE_",
    )
    parser.add_argument(
        "--include-all",
        action="store_true",
        help="Include all sampler labels. Use only for debugging, not official benchmark comparison.",
    )
    args = parser.parse_args()

    files = sorted(args.root.rglob("*.jtl"))
    if not files:
        print(f"No .jtl files found under {args.root}")
        return 1

    rows = [summarize_file(p, include_all=args.include_all, label_prefix=args.label_prefix) for p in files]
    measurement_rows = [r for r in rows if int(r.get("samples", 0)) > 0]
    if not measurement_rows:
        print(f"No measurement rows found under {args.root}. Expected labels starting with {args.label_prefix!r}.")
        print("Use --include-all only for debugging if you intentionally want setup/DISCARD rows included.")
        return 2

    rows_with_agg = measurement_rows + aggregate(measurement_rows)

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
    print(f"Filter mode: {'all labels' if args.include_all else 'labels starting with ' + args.label_prefix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
