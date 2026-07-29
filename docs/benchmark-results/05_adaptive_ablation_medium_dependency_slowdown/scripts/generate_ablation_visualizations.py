import os
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import argparse
from pathlib import Path

def setup_style():
    sns.set_theme(style="whitegrid")
    plt.rcParams.update({
        'font.size': 12,
        'axes.labelsize': 12,
        'axes.titlesize': 14,
        'xtick.labelsize': 10,
        'ytick.labelsize': 10,
        'figure.dpi': 300
    })

def save_plot(fig, path_without_ext):
    fig.savefig(f"{path_without_ext}.png", bbox_inches='tight', dpi=300)
    fig.savefig(f"{path_without_ext}.svg", bbox_inches='tight', format='svg')
    plt.close(fig)

def add_bar_labels(ax, fmt='%.1f'):
    for container in ax.containers:
        ax.bar_label(container, fmt=fmt, padding=3, size=9)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=str, required=True)
    parser.add_argument("--out-dir", type=str, required=True)
    args = parser.parse_args()

    data_dir = Path(args.data_dir).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    agg_file = data_dir / "aggregate_results.csv"
    diff_file = data_dir / "relative_difference.csv"
    run_file = data_dir / "run_level_results.csv"

    if not agg_file.exists():
        print("aggregate_results.csv not found!")
        return

    df_agg = pd.read_csv(agg_file)
    df_diff = pd.read_csv(diff_file) if diff_file.exists() else None
    df_run = pd.read_csv(run_file) if run_file.exists() else None

    # Label mapping for cleaner display
    LABEL_MAP = {
        "adaptive_full": "Adaptive Full",
        "adaptive_fixed_weights": "Fixed Weights",
        "adaptive_no_capacity": "No Capacity",
        "adaptive_no_ewma_latency": "No EWMA",
        "adaptive_no_low_load_rr": "No Low-load RR",
        "adaptive_no_p2c": "No P2C",
        "adaptive_no_pid": "No PID",
        "adaptive_no_probe": "No Probe",
        "adaptive_no_score_ema": "No Score EMA"
    }

    df_agg['strategy'] = df_agg['strategy'].map(lambda x: LABEL_MAP.get(x, x))
    if df_diff is not None:
        df_diff['strategy'] = df_diff['strategy'].map(lambda x: LABEL_MAP.get(x, x))
    if df_run is not None:
        df_run['strategy'] = df_run['strategy'].map(lambda x: LABEL_MAP.get(x, x))

    # Sort strategies to ensure 'Adaptive Full' is first, then alphabetical
    strategies = df_agg['strategy'].tolist()
    if 'Adaptive Full' in strategies:
        strategies.remove('Adaptive Full')
        strategies = ['Adaptive Full'] + sorted(strategies)
    
    df_agg['strategy'] = pd.Categorical(df_agg['strategy'], categories=strategies, ordered=True)
    df_agg = df_agg.sort_values('strategy')

    setup_style()
    colors = sns.color_palette("husl", len(strategies))

    # Fig 01: Avg Latency
    fig, ax = plt.subplots(figsize=(12, 6))
    sns.barplot(data=df_agg, x='strategy', y='average_latency', palette=colors, ax=ax)
    add_bar_labels(ax)
    ax.set_title("01 - Average Latency Comparison")
    ax.set_ylabel("Latency (ms)")
    plt.xticks(rotation=45, ha='right')
    save_plot(fig, out_dir / "fig_01_avg_latency")

    # Fig 02: P95
    fig, ax = plt.subplots(figsize=(12, 6))
    sns.barplot(data=df_agg, x='strategy', y='p95_latency', palette=colors, ax=ax)
    add_bar_labels(ax)
    ax.set_title("02 - P95 Latency Comparison")
    ax.set_ylabel("Latency (ms)")
    plt.xticks(rotation=45, ha='right')
    save_plot(fig, out_dir / "fig_02_p95_latency")

    # Fig 03: P99
    fig, ax = plt.subplots(figsize=(12, 6))
    sns.barplot(data=df_agg, x='strategy', y='p99_latency', palette=colors, ax=ax)
    add_bar_labels(ax)
    ax.set_title("03 - P99 Latency Comparison")
    ax.set_ylabel("Latency (ms)")
    plt.xticks(rotation=45, ha='right')
    save_plot(fig, out_dir / "fig_03_p99_latency")

    # Fig 04 & 06: Throughput vs Goodput
    fig, ax = plt.subplots(figsize=(12, 6))
    df_melt = pd.melt(df_agg, id_vars=['strategy'], value_vars=['throughput', 'goodput'], var_name='Metric', value_name='RPS')
    sns.barplot(data=df_melt, x='strategy', y='RPS', hue='Metric', ax=ax)
    add_bar_labels(ax)
    ax.set_title("04 & 06 - Throughput and Goodput")
    plt.xticks(rotation=45, ha='right')
    save_plot(fig, out_dir / "fig_04_06_throughput_goodput")

    # Fig 05: Error Rate
    fig, ax = plt.subplots(figsize=(12, 6))
    sns.barplot(data=df_agg, x='strategy', y='error_rate_pooled', palette=colors, ax=ax)
    add_bar_labels(ax, fmt='%.3f')
    ax.set_title("05 - Error Rate Comparison")
    ax.set_ylabel("Error Rate (%)")
    plt.xticks(rotation=45, ha='right')
    save_plot(fig, out_dir / "fig_05_error_rate")

    # Fig 07: Stability (Bar with Error Bars)
    if df_run is not None and len(df_run) > 0:
        fig, ax = plt.subplots(figsize=(12, 6))
        sns.barplot(data=df_run, x='strategy', y='average_latency', errorbar='sd', capsize=.2, palette=colors, ax=ax)
        add_bar_labels(ax)
        ax.set_title("07 - Latency Stability (Mean ± SD)")
        ax.set_ylabel("Average Latency (ms)")
        plt.xticks(rotation=45, ha='right')
        save_plot(fig, out_dir / "fig_07_stability")

        # Fig 08: Boxplot
        fig, ax = plt.subplots(figsize=(12, 6))
        sns.boxplot(data=df_run, x='strategy', y='average_latency', palette=colors, ax=ax)
        ax.set_title("08 - Latency Distribution across runs")
        plt.xticks(rotation=45, ha='right')
        save_plot(fig, out_dir / "fig_08_distribution")

    # Fig 09: Relative Difference
    if df_diff is not None:
        df_diff_melt = pd.melt(df_diff, id_vars=['strategy'], 
                               value_vars=['average_latency_diff_pct', 'p99_latency_diff_pct', 'error_rate_pooled_diff_pct', 'goodput_diff_pct'],
                               var_name='Metric', value_name='Difference (%)')
        
        # Keep Adaptive Full so it shows as 0% reference
        
        fig, ax = plt.subplots(figsize=(14, 8))
        sns.barplot(data=df_diff_melt, x='strategy', y='Difference (%)', hue='Metric', ax=ax)
        add_bar_labels(ax)
        ax.axhline(0, color='black', linewidth=1.5)
        ax.set_title("09 - Relative Difference vs Adaptive Full (Baseline = 0%)")
        plt.xticks(rotation=45, ha='right')
        plt.legend(title='Metric', bbox_to_anchor=(1.05, 1), loc='upper left')
        save_plot(fig, out_dir / "fig_09_relative_difference")

    print("[SUCCESS] All 9 visualizations generated in PNG and SVG formats (300 DPI).")

if __name__ == "__main__":
    main()
