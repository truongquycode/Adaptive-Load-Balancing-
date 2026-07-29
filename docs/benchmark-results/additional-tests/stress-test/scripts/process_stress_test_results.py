import os
import csv
import math
import argparse
from pathlib import Path
from statistics import mean, pstdev
from datetime import datetime

DEFAULT_LABEL_PREFIX = "MEASURE_"

def percentile(values, q):
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

def get_case_insensitive(row, key, default=""):
    if key in row:
        return row.get(key, default) or default
    lower_key = key.lower()
    for k, v in row.items():
        if k.lower() == lower_key:
            return v or default
    return default

def should_include_row(row, label_prefix):
    label = get_case_insensitive(row, "label", "").strip()
    return label.startswith(label_prefix)

def check_jtl_readable(jtl_path):
    try:
        with jtl_path.open("r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            return reader.fieldnames is not None
    except Exception:
        return False

def parse_run(jtl_path, label_prefix):
    elapsed = []
    timestamps = []
    errors = 0
    included = 0
    excluded = 0
    
    with jtl_path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if not should_include_row(row, label_prefix):
                excluded += 1
                continue
            
            try:
                e = float(get_case_insensitive(row, "elapsed", ""))
            except ValueError:
                excluded += 1
                continue
                
            elapsed.append(e)
            included += 1
            
            success = str(get_case_insensitive(row, "success", "true")).strip().lower()
            if success not in {"true", "1", "yes", "true", "True", "TRUE"}:
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

    return {
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
        "min_ms": min(elapsed) if elapsed else float("nan"),
        "max_ms": max(elapsed) if elapsed else float("nan"),
    }

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", type=str, required=True, help="Path to raw stress-test directory")
    parser.add_argument("--out-dir", type=str, required=True, help="Path to output standardized directory")
    args = parser.parse_args()

    raw_dir = Path(args.raw_dir).resolve()
    out_dir = Path(args.out_dir).resolve()

    data_out = out_dir / "data"
    metadata_out = out_dir / "metadata"
    
    data_out.mkdir(parents=True, exist_ok=True)
    metadata_out.mkdir(parents=True, exist_ok=True)

    strategies = ["adaptive_full", "least_connect", "random", "round_robin"]
    
    validation_rows = []
    summary_rows = []
    
    total_jtl_found = 0
    valid_runs = 0
    invalid_runs = 0
    warnings = []
    
    print(f"Scanning raw root: {raw_dir}")

    for strategy in strategies:
        strat_dir = raw_dir / strategy
        

        if not strat_dir.exists():
            warnings.append(f"Missing strategy directory: {strategy}")
            continue

        for i in range(1, 4):
            run_name = f"{strategy}-{i}"
            run_dir = strat_dir / run_name
            jtl_file = strat_dir / f"{run_name}.jtl"
            metadata_file = strat_dir / f"{run_name}-metadata.txt"

            has_jtl = jtl_file.exists()
            has_metadata = metadata_file.exists()
            has_html = run_dir.exists() and (run_dir / "index.html").exists()
            is_readable = check_jtl_readable(jtl_file) if has_jtl else False
            
            if has_jtl:
                total_jtl_found += 1

            validation = {
                "strategy": strategy,
                "run": run_name,
                "jtl_found": has_jtl,
                "jtl_readable": is_readable,
                "metadata_found": has_metadata,
                "html_report_found": has_html,
                "sample_count": 0,
                "status": "VALID",
                "warnings": ""
            }

            if not has_jtl or not is_readable:
                validation["status"] = "INVALID"
                validation["warnings"] = "JTL missing or unreadable"
                invalid_runs += 1
                validation_rows.append(validation)
                print(f"[INVALID] {run_name}: JTL missing or unreadable")
                continue

            print(f"Parsing JTL: {jtl_file.name}...")
            # Parse the run
            metrics = parse_run(jtl_file, DEFAULT_LABEL_PREFIX)
            validation["sample_count"] = metrics["samples"]
            
            if metrics["samples"] == 0:
                validation["status"] = "INVALID"
                validation["warnings"] = "0 valid samples found"
                invalid_runs += 1
                print(f"[INVALID] {run_name}: 0 MEASURE_ samples")
            else:
                valid_runs += 1
                print(f"[OK] {run_name}: {metrics['samples']} samples, Throughput: {metrics['throughput_rps']:.2f}")

            validation_rows.append(validation)

            # Build summary row
            summary = {
                "scenario": "stress-test",
                "strategy": strategy,
                "run": run_name,
                "total_samples": metrics["samples"],
                "excluded_samples": metrics["excluded_samples"],
                "error_samples": metrics["errors"],
                "error_rate": metrics["error_rate_percent"],
                "average_latency": metrics["avg_ms"],
                "median_latency": metrics["p50_ms"],
                "p95_latency": metrics["p95_ms"],
                "p99_latency": metrics["p99_ms"],
                "min_latency": metrics["min_ms"],
                "max_latency": metrics["max_ms"],
                "throughput": metrics["throughput_rps"],
                "duration": metrics["duration_s"]
            }
            summary_rows.append(summary)

    print("\nExporting CSV reports...")
    # Export validation report
    with open(data_out / "validation-report.csv", "w", newline="", encoding="utf-8") as f:
        if validation_rows:
            writer = csv.DictWriter(f, fieldnames=validation_rows[0].keys())
            writer.writeheader()
            writer.writerows(validation_rows)

    # Export JTL summary
    with open(data_out / "jtl-summary.csv", "w", newline="", encoding="utf-8") as f:
        if summary_rows:
            writer = csv.DictWriter(f, fieldnames=summary_rows[0].keys())
            writer.writeheader()
            writer.writerows(summary_rows)

    # Aggregate
    agg_rows = []
    mean_std_rows = []
    
    # Group by strategy
    groups = {}
    for r in summary_rows:
        groups.setdefault(r["strategy"], []).append(r)
        
    for strategy, rs in sorted(groups.items()):
        metrics = ["average_latency", "median_latency", "p95_latency", "p99_latency", "min_latency", "max_latency", "throughput", "error_rate"]
        
        # 1. Aggregate Summary
        total_samples = sum(r["total_samples"] for r in rs)
        total_errors = sum(r["error_samples"] for r in rs)
        pooled_error_rate = (total_errors / total_samples * 100) if total_samples > 0 else 0
        
        agg = {
            "scenario": "stress-test",
            "strategy": strategy,
            "run": "mean_of_runs",
            "runs_count": len(rs),
            "total_samples": total_samples,
            "error_samples": total_errors,
            "pooled_error_rate": pooled_error_rate
        }
        
        std_metrics = {
            "strategy": strategy
        }
        
        for m in metrics:
            vals = [r[m] for r in rs if not math.isnan(r[m])]
            m_mean = mean(vals) if vals else float("nan")
            m_std = pstdev(vals) if len(vals) > 1 else 0.0
            
            agg[f"mean_of_runs_{m}"] = m_mean
            
            std_metrics[f"{m}_mean"] = m_mean
            std_metrics[f"{m}_std"] = m_std
            std_metrics[f"{m}_min"] = min(vals) if vals else float("nan")
            std_metrics[f"{m}_max"] = max(vals) if vals else float("nan")
            
        agg_rows.append(agg)
        mean_std_rows.append(std_metrics)

    with open(data_out / "aggregate-summary.csv", "w", newline="", encoding="utf-8") as f:
        if agg_rows:
            writer = csv.DictWriter(f, fieldnames=agg_rows[0].keys())
            writer.writeheader()
            writer.writerows(agg_rows)

    with open(data_out / "strategy-mean-std.csv", "w", newline="", encoding="utf-8") as f:
        if mean_std_rows:
            writer = csv.DictWriter(f, fieldnames=mean_std_rows[0].keys())
            writer.writeheader()
            writer.writerows(mean_std_rows)

    print("Generating data-provenance.md...")
    # Write provenance
    with open(metadata_out / "data-provenance.md", "w", encoding="utf-8") as f:
        f.write("# Data Provenance: Stress Test\n\n")
        f.write(f"- **Raw source**: {raw_dir}\n")
        f.write(f"- **Processed output**: {out_dir}\n")
        f.write(f"- **Processing script**: {Path(__file__).resolve()}\n")
        f.write(f"- **Processing timestamp**: {datetime.now().isoformat()}\n")
        f.write(f"- **Expected strategies**: 4\n")
        f.write(f"- **Expected runs**: 12\n")
        f.write(f"- **Total JTL files found**: {total_jtl_found}\n")
        f.write(f"- **Valid runs processed**: {valid_runs}\n")
        f.write(f"- **Invalid runs**: {invalid_runs}\n")
        
        if warnings:
            f.write("\n## Warnings\n")
            for w in warnings:
                f.write(f"- {w}\n")
        
    print(f"\n=============================")
    print(f"Total JTL Found: {total_jtl_found}")
    print(f"Valid Runs: {valid_runs}")
    print(f"Invalid Runs: {invalid_runs}")
    print(f"=============================\n")

if __name__ == "__main__":
    main()
