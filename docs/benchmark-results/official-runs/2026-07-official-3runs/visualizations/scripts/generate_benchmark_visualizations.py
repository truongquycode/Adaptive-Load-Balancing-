import os
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import argparse
from pathlib import Path
import sys

# Config matplotlib
plt.rcParams['figure.figsize'] = (10, 6)
plt.rcParams['font.family'] = 'sans-serif'
plt.rcParams['font.sans-serif'] = ['Arial', 'Helvetica', 'DejaVu Sans']
plt.rcParams['axes.spines.top'] = False
plt.rcParams['axes.spines.right'] = False

# Colors for consistency
COLOR_MAP = {
    'Adaptive': '#2E86C1',        # Professional Blue
    'Least Connections': '#28B463', # Green
    'Random': '#F1C40F',          # Yellow
    'Round Robin': '#E74C3C'      # Red
}

def parse_args(benchmark_root, visualizations_root):
    parser = argparse.ArgumentParser(description='Generate Benchmark Visualizations')
    parser.add_argument('--input', type=str, default=str(benchmark_root), help='Path to the official-3runs directory (Benchmark root)')
    parser.add_argument('--output', type=str, default=str(visualizations_root), help='Path to output visualizations directory')
    return parser.parse_args()

def safe_goodput(row):
    if pd.notnull(row.get('duration_s')) and row['duration_s'] > 0:
        successful_requests = row['samples'] - row['errors']
        return successful_requests / row['duration_s']
    return np.nan

def load_and_recalculate(base_path):
    all_runs = []
    base_dir = Path(base_path)
    
    print("\nChecking:")
    scenarios = ['01-low-load', '02-medium-dependency-slowdown', '03-high-dependency-slowdown']
    
    for scenario in scenarios:
        jtl_path = base_dir / scenario / 'data' / 'jtl-summary.csv'
        relative_display = f"{scenario}/data/jtl-summary.csv"
        if not jtl_path.exists():
            print(f"[ERROR] {jtl_path} not found!")
            continue
            
        print(f"[OK] {relative_display}")
        df = pd.read_csv(jtl_path)
        
        # Lọc ra các dòng của từng Run cụ thể, loại bỏ dòng AGGREGATE
        runs_df = df[df['file'] != 'AGGREGATE'].copy()
        runs_df['scenario'] = scenario
        
        # Sửa tên strategy cho đẹp
        strategy_mapping = {
            'adaptive_full': 'Adaptive',
            'least_connect': 'Least Connections',
            'random': 'Random',
            'round_robin': 'Round Robin'
        }
        runs_df['strategy_name'] = runs_df['strategy_folder'].map(strategy_mapping)
        
        # Tính goodput cho từng run
        runs_df['goodput'] = runs_df.apply(safe_goodput, axis=1)
        
        all_runs.append(runs_df)
        
    if not all_runs:
        return pd.DataFrame(), pd.DataFrame()
        
    combined_runs = pd.concat(all_runs, ignore_index=True)
    
    # Tính aggregate metric
    aggs = []
    grouped = combined_runs.groupby(['scenario', 'strategy_name'])
    
    for (scenario, strategy), group in grouped:
        total_samples = group['samples'].sum()
        total_errors = group['errors'].sum()
        
        pooled_error_rate = (total_errors / total_samples) * 100 if total_samples > 0 else 0
        
        agg_row = {
            'scenario': scenario,
            'strategy_name': strategy,
            'runs_count': len(group),
            'total_requests': total_samples,
            'total_errors': total_errors,
            'pooled_error_rate': pooled_error_rate,
            'mean_error_rate': group['error_rate_percent'].mean(),
            'mean_avg_latency': group['avg_ms'].mean(),
            'std_avg_latency': group['avg_ms'].std() if len(group) > 1 else 0,
            'mean_median_latency': group['p50_ms'].mean(),
            'mean_p90_latency': group['p90_ms'].mean(),
            'mean_p95_latency': group['p95_ms'].mean(),
            'std_p95_latency': group['p95_ms'].std() if len(group) > 1 else 0,
            'mean_p99_latency': group['p99_ms'].mean(),
            'mean_throughput': group['throughput_rps'].mean(),
            'mean_goodput': group['goodput'].mean()
        }
        aggs.append(agg_row)
        
    aggregate_df = pd.DataFrame(aggs)
    
    return combined_runs, aggregate_df

def plot_fig_01_avg_latency(agg_df, out_dir):
    fig, ax = plt.subplots(figsize=(10, 6))
    
    scenarios = ['01-low-load', '02-medium-dependency-slowdown', '03-high-dependency-slowdown']
    labels = ['Low 300 RPS', 'Medium 600 RPS', 'High 900 RPS']
    strategies = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    
    x = np.arange(len(labels))
    width = 0.2
    
    for i, strategy in enumerate(strategies):
        y_values = []
        for s in scenarios:
            val = agg_df[(agg_df['scenario'] == s) & (agg_df['strategy_name'] == strategy)]['mean_avg_latency']
            y_values.append(val.values[0] if len(val) > 0 else 0)
        
        pos = x - 1.5*width + i*width
        bars = ax.bar(pos, y_values, width, label=strategy, color=COLOR_MAP.get(strategy, 'gray'))
        for bar in bars:
            height = bar.get_height()
            if height > 0:
                ax.annotate(f'{height:.1f}',
                            xy=(bar.get_x() + bar.get_width() / 2, height),
                            xytext=(0, 3), textcoords="offset points",
                            ha='center', va='bottom', fontsize=7.5)
        
    ax.set_ylabel('Average Latency (ms)')
    ax.set_title('Figure 01: Average Latency by Load')
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)
    
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_01_avg_latency_by_load.png', dpi=300)
    plt.savefig(out_dir / 'fig_01_avg_latency_by_load.svg')
    plt.close()

def plot_fig_02_p95_latency(agg_df, out_dir):
    fig, ax = plt.subplots(figsize=(10, 6))
    
    scenarios = ['01-low-load', '02-medium-dependency-slowdown', '03-high-dependency-slowdown']
    labels = ['Low 300 RPS', 'Medium 600 RPS', 'High 900 RPS']
    strategies = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    
    x = np.arange(len(labels))
    width = 0.2
    
    for i, strategy in enumerate(strategies):
        y_values = []
        for s in scenarios:
            val = agg_df[(agg_df['scenario'] == s) & (agg_df['strategy_name'] == strategy)]['mean_p95_latency']
            y_values.append(val.values[0] if len(val) > 0 else 0)
        
        pos = x - 1.5*width + i*width
        bars = ax.bar(pos, y_values, width, label=strategy, color=COLOR_MAP.get(strategy, 'gray'))
        for bar in bars:
            height = bar.get_height()
            if height > 0:
                ax.annotate(f'{height:.1f}',
                            xy=(bar.get_x() + bar.get_width() / 2, height),
                            xytext=(0, 3), textcoords="offset points",
                            ha='center', va='bottom', fontsize=7.5)
        
    ax.set_ylabel('Mean P95 Latency (ms)\n*Mean P95 across 3 independent runs*')
    ax.set_title('Figure 02: P95 Latency')
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)
    
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_02_p95_latency.png', dpi=300)
    plt.savefig(out_dir / 'fig_02_p95_latency.svg')
    plt.close()

def plot_fig_03_p99_latency(agg_df, out_dir):
    fig, ax = plt.subplots(figsize=(10, 6))
    
    scenarios = ['01-low-load', '02-medium-dependency-slowdown', '03-high-dependency-slowdown']
    labels = ['Low 300 RPS', 'Medium 600 RPS', 'High 900 RPS']
    strategies = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    
    x = np.arange(len(labels))
    width = 0.2
    
    for i, strategy in enumerate(strategies):
        y_values = []
        for s in scenarios:
            val = agg_df[(agg_df['scenario'] == s) & (agg_df['strategy_name'] == strategy)]['mean_p99_latency']
            y_values.append(val.values[0] if len(val) > 0 else 0)
        
        pos = x - 1.5*width + i*width
        bars = ax.bar(pos, y_values, width, label=strategy, color=COLOR_MAP.get(strategy, 'gray'))
        for bar in bars:
            height = bar.get_height()
            if height > 0:
                ax.annotate(f'{height:.1f}',
                            xy=(bar.get_x() + bar.get_width() / 2, height),
                            xytext=(0, 3), textcoords="offset points",
                            ha='center', va='bottom', fontsize=7.5)
        
    ax.set_ylabel('Mean P99 Latency (ms)\n*Mean P99 across 3 independent runs*')
    ax.set_title('Figure 03: P99 Latency')
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)
    
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_03_p99_latency.png', dpi=300)
    plt.savefig(out_dir / 'fig_03_p99_latency.svg')
    plt.close()

def plot_fig_04_error_rate(agg_df, out_dir):
    fig, ax = plt.subplots(figsize=(10, 6))
    
    scenarios = ['01-low-load', '02-medium-dependency-slowdown', '03-high-dependency-slowdown']
    labels = ['Low 300 RPS', 'Medium 600 RPS', 'High 900 RPS']
    strategies = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    
    for strategy in strategies:
        y_values = []
        for s in scenarios:
            val = agg_df[(agg_df['scenario'] == s) & (agg_df['strategy_name'] == strategy)]['pooled_error_rate']
            y_values.append(val.values[0] if len(val) > 0 else 0)
            
        ax.plot(labels, y_values, marker='o', linewidth=2, label=strategy, color=COLOR_MAP.get(strategy, 'gray'))
        for j, y_val in enumerate(y_values):
            label_text = '0%' if y_val == 0 else (f'{y_val:.2f}%' if y_val > 0.1 else f'{y_val:.4f}%')
            ax.annotate(label_text, xy=(j, y_val), xytext=(0, 6), textcoords="offset points",
                        ha='center', va='bottom', fontsize=8, bbox=dict(facecolor='white', alpha=0.7, edgecolor='none', pad=0))
        
    ax.set_ylabel('Pooled Error Rate (%) - Log Scale')
    ax.set_title('Figure 04: Error Rate by Load')
    ax.set_yscale('symlog', linthresh=0.01)
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)
    
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_04_error_rate_by_load.png', dpi=300)
    plt.savefig(out_dir / 'fig_04_error_rate_by_load.svg')
    plt.close()

def plot_fig_05_throughput_vs_goodput(agg_df, out_dir):
    fig, ax = plt.subplots(figsize=(12, 6))
    
    high_df = agg_df[agg_df['scenario'] == '03-high-dependency-slowdown'].copy()
    if high_df.empty:
        return
        
    strategies = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    
    tpt = []
    gpt = []
    
    for strategy in strategies:
        row = high_df[high_df['strategy_name'] == strategy]
        tpt.append(row['mean_throughput'].values[0] if len(row) > 0 else 0)
        gpt.append(row['mean_goodput'].values[0] if len(row) > 0 else 0)
        
    x = np.arange(len(strategies))
    width = 0.35
    
    bar1 = ax.bar(x - width/2, tpt, width, label='Mean Throughput', color='#95A5A6')
    bar2 = ax.bar(x + width/2, gpt, width, label='Goodput (Successful RPS)', color='#3498DB')
    
    for bars in [bar1, bar2]:
        for bar in bars:
            height = bar.get_height()
            if height > 0:
                ax.annotate(f'{height:.1f}',
                            xy=(bar.get_x() + bar.get_width() / 2, height),
                            xytext=(0, 3), textcoords="offset points",
                            ha='center', va='bottom', fontsize=9)
    
    ax.set_ylabel('Requests per Second')
    ax.set_title('Figure 05: Throughput vs Goodput (High Load 900 RPS)')
    ax.set_xticks(x)
    ax.set_xticklabels(strategies)
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)
    
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_05_throughput_vs_goodput.png', dpi=300)
    plt.savefig(out_dir / 'fig_05_throughput_vs_goodput.svg')
    plt.close()

def plot_fig_06_improvement(agg_df, out_dir, imp_df):
    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(18, 6))
    
    metrics = ['Avg Latency', 'P95', 'P99', 'Error Rate']
    y_pos = np.arange(len(metrics))
    
    def get_imp(baseline, metric):
        val = imp_df[(imp_df['baseline'] == baseline) & (imp_df['metric'] == metric)]['improvement']
        return val.values[0] if not val.empty else 0
        
    def plot_ax(ax, baseline):
        imp_vals = [
            get_imp(baseline, 'Avg Latency'),
            get_imp(baseline, 'Mean P95'),
            get_imp(baseline, 'Mean P99'),
            get_imp(baseline, 'Pooled Error Rate')
        ]
        ax.barh(y_pos, imp_vals, align='center', color='#2ECC71')
        ax.set_yticks(y_pos)
        ax.set_yticklabels(metrics)
        ax.invert_yaxis()
        ax.set_xlabel('Improvement (%)')
        ax.set_title(f'Adaptive vs {baseline}')
        ax.set_xlim(0, 105)
        for i, v in enumerate(imp_vals):
            prefix = "+" if v >= 0 else ""
            ax.text(v + 1, i, f"{prefix}{v:.1f}%", va='center')
            
    plot_ax(ax1, 'Least Connections')
    plot_ax(ax2, 'Random')
    plot_ax(ax3, 'Round Robin')
    
    fig.suptitle('Figure 06: Adaptive Load Balancer Improvements (High Load)', fontsize=14)
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_06_adaptive_improvement.png', dpi=300)
    plt.savefig(out_dir / 'fig_06_adaptive_improvement.svg')
    plt.close()

def plot_fig_07_stability(agg_df, out_dir):
    fig, ax = plt.subplots(figsize=(10, 6))
    
    high_df = agg_df[agg_df['scenario'] == '03-high-dependency-slowdown'].copy()
    if high_df.empty:
        return
        
    strategies = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    
    means = []
    stds = []
    
    for strategy in strategies:
        row = high_df[high_df['strategy_name'] == strategy]
        means.append(row['mean_avg_latency'].values[0] if len(row) > 0 else 0)
        stds.append(row['std_avg_latency'].values[0] if len(row) > 0 else 0)
        
    x = np.arange(len(strategies))
    
    colors = [COLOR_MAP.get(s, 'gray') for s in strategies]
    
    bars = ax.bar(x, means, yerr=stds, align='center', alpha=0.8, ecolor='black', capsize=10, color=colors)
    
    for bar in bars:
        height = bar.get_height()
        if height > 0:
            ax.annotate(f'{height:.1f}',
                        xy=(bar.get_x() + bar.get_width() / 2, height / 2),
                        ha='center', va='center', fontsize=10, fontweight='bold', color='white',
                        bbox=dict(facecolor='black', alpha=0.5, edgecolor='none', boxstyle='round,pad=0.2'))
    ax.set_ylabel('Mean Avg Latency (ms)')
    ax.set_xticks(x)
    ax.set_xticklabels(strategies)
    ax.set_title('Figure 07: Stability Mean ± Standard Deviation (High Load, n=3)')
    ax.yaxis.grid(True, linestyle='--', alpha=0.7)
    
    for i, (mean, std) in enumerate(zip(means, stds)):
        if mean > 0:
            cv = (std / mean) * 100
            ax.text(i, mean + std + 50, f"CV: {cv:.1f}%", ha='center', va='bottom', fontsize=10, fontweight='bold')
    
    plt.tight_layout()
    plt.savefig(out_dir / 'fig_07_stability_mean_std.png', dpi=300)
    plt.savefig(out_dir / 'fig_07_stability_mean_std.svg')
    plt.close()

def calculate_improvements(agg_df):
    high_df = agg_df[agg_df['scenario'] == '03-high-dependency-slowdown']
    
    if high_df.empty:
        return pd.DataFrame()
        
    adaptive = high_df[high_df['strategy_name'] == 'Adaptive'].iloc[0]
    baselines = ['Least Connections', 'Random', 'Round Robin']
    
    imp_records = []
    for baseline in baselines:
        base_row = high_df[high_df['strategy_name'] == baseline]
        if base_row.empty: continue
        base_row = base_row.iloc[0]
        
        metrics = [
            ('Avg Latency', 'mean_avg_latency'),
            ('Mean P95', 'mean_p95_latency'),
            ('Mean P99', 'mean_p99_latency'),
            ('Pooled Error Rate', 'pooled_error_rate')
        ]
        
        for metric_name, col_name in metrics:
            base_val = base_row[col_name]
            adapt_val = adaptive[col_name]
            
            if base_val > 0:
                imp = ((base_val - adapt_val) / base_val) * 100
            else:
                imp = 0
                
            imp_records.append({
                'baseline': baseline,
                'metric': metric_name,
                'baseline_value': base_val,
                'adaptive_value': adapt_val,
                'improvement': imp
            })
            
    return pd.DataFrame(imp_records)

def main():
    # Resolve the exact paths relative to this script
    script_dir = Path(__file__).resolve().parent
    visualizations_root = script_dir.parent
    benchmark_root = visualizations_root.parent
    
    args = parse_args(benchmark_root, visualizations_root)
    base_dir = Path(args.input).resolve()
    out_dir = Path(args.output).resolve()
    
    print("=" * 60)
    print(f"Script directory:          {script_dir}")
    print(f"Benchmark root:            {base_dir}")
    print(f"Visualization output root: {out_dir}")
    print("=" * 60)
    
    dirs = ['charts', 'data']
    for d in dirs:
        (out_dir / d).mkdir(parents=True, exist_ok=True)
        
    combined_runs, aggregate_df = load_and_recalculate(base_dir)
    
    if combined_runs.empty:
        print("\n[FAILED] No data found! Please check your INPUT_ROOT.")
        sys.exit(1)
        
    print("\nSaving recalculated data...")
    combined_runs.to_csv(out_dir / 'data' / 'recalculated_run_metrics.csv', index=False)
    aggregate_df.to_csv(out_dir / 'data' / 'recalculated_aggregate_metrics.csv', index=False)
    print(f"[OK] Saved to {out_dir / 'data'}")
    
    imp_df = calculate_improvements(aggregate_df)
    if not imp_df.empty:
        imp_df.to_csv(out_dir / 'data' / 'improvement_metrics.csv', index=False)
        
    print("\nGenerating visualizations...")
    charts_dir = out_dir / 'charts'
    plot_fig_01_avg_latency(aggregate_df, charts_dir)
    plot_fig_02_p95_latency(aggregate_df, charts_dir)
    plot_fig_03_p99_latency(aggregate_df, charts_dir)
    plot_fig_04_error_rate(aggregate_df, charts_dir)
    plot_fig_05_throughput_vs_goodput(aggregate_df, charts_dir)
    if not imp_df.empty:
        plot_fig_06_improvement(aggregate_df, charts_dir, imp_df)
    plot_fig_07_stability(aggregate_df, charts_dir)
    
    print(f"[OK] Generated 7 benchmark figures at {charts_dir}")
    print("\nPipeline finished successfully!")

if __name__ == "__main__":
    main()
