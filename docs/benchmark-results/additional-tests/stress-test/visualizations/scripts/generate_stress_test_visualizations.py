import os
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path

def prepare_directories(base_path):
    data_dir = base_path / 'data'
    charts_dir = base_path / 'charts'
    data_dir.mkdir(parents=True, exist_ok=True)
    charts_dir.mkdir(parents=True, exist_ok=True)
    return data_dir, charts_dir

def generate_csv_reports(src_data_dir, dest_data_dir):
    # 1. Read source data
    jtl_summary_path = src_data_dir / 'jtl-summary.csv'
    agg_summary_path = src_data_dir / 'aggregate-summary.csv'
    mean_std_path = src_data_dir / 'strategy-mean-std.csv'
    
    if not jtl_summary_path.exists():
        raise FileNotFoundError(f"Missing {jtl_summary_path}")

    df_run = pd.read_csv(jtl_summary_path)
    df_agg = pd.read_csv(agg_summary_path)
    df_std = pd.read_csv(mean_std_path)

    # Calculate Goodput for run-level
    df_run['successful_samples'] = df_run['total_samples'] - df_run['error_samples']
    # Goodput RPS = Throughput * (successful_samples / total_samples)
    # Equivalently = successful_samples / duration, assuming throughput = total/duration
    df_run['goodput_rps'] = df_run.apply(
        lambda row: (row['throughput'] * (row['successful_samples'] / row['total_samples'])) if row['total_samples'] > 0 else 0,
        axis=1
    )

    # Calculate Goodput for aggregate
    df_agg['successful_samples'] = df_agg['total_samples'] - df_agg['error_samples']
    # Mean of run-level Goodput
    agg_goodput_map = df_run.groupby('strategy')['goodput_rps'].mean().to_dict()
    df_agg['mean_of_runs_goodput_rps'] = df_agg['strategy'].map(agg_goodput_map)

    # 4.1 01_stress_test_run_level.csv
    df_run_out = df_run[['scenario', 'strategy', 'run', 'total_samples', 'error_samples', 
                         'successful_samples', 'error_rate', 'throughput', 'goodput_rps', 
                         'average_latency', 'median_latency', 'p95_latency', 'p99_latency', 
                         'min_latency', 'max_latency']].copy()
    df_run_out.rename(columns={'error_samples': 'total_errors'}, inplace=True)
    df_run_out.to_csv(dest_data_dir / '01_stress_test_run_level.csv', index=False)

    # 4.2 02_stress_test_aggregate.csv
    df_agg_out = df_agg[['strategy', 'mean_of_runs_average_latency', 'mean_of_runs_median_latency',
                         'mean_of_runs_p95_latency', 'mean_of_runs_p99_latency', 'mean_of_runs_throughput',
                         'mean_of_runs_goodput_rps', 'error_samples', 'mean_of_runs_error_rate', 'pooled_error_rate']].copy()
    
    # Merge with STD from df_std
    # Columns in std: strategy, average_latency_std, ...
    std_cols = [col for col in df_std.columns if col.endswith('_std')]
    df_std_out = df_std[['strategy'] + std_cols].copy()
    
    df_agg_out = pd.merge(df_agg_out, df_std_out, on='strategy', how='left')
    
    # Rename to clean names
    rename_map = {
        'mean_of_runs_average_latency': 'avg_latency',
        'mean_of_runs_median_latency': 'p50',
        'mean_of_runs_p95_latency': 'p95',
        'mean_of_runs_p99_latency': 'p99',
        'mean_of_runs_throughput': 'throughput',
        'mean_of_runs_goodput_rps': 'goodput',
        'error_samples': 'total_errors',
        'mean_of_runs_error_rate': 'error_rate_mean',
        'pooled_error_rate': 'error_rate_pooled',
        'average_latency_std': 'std_avg_latency',
        'median_latency_std': 'std_p50',
        'p95_latency_std': 'std_p95',
        'p99_latency_std': 'std_p99',
        'throughput_std': 'std_throughput',
        'error_rate_std': 'std_error_rate'
    }
    df_agg_out.rename(columns=rename_map, inplace=True)
    df_agg_out.to_csv(dest_data_dir / '02_stress_test_aggregate.csv', index=False)

    # 4.3 03_stress_test_mean_std.csv
    df_mean_std = df_agg_out[['strategy', 'avg_latency', 'std_avg_latency', 'p50', 'std_p50',
                              'p95', 'std_p95', 'p99', 'std_p99', 'throughput', 'std_throughput',
                              'error_rate_mean', 'std_error_rate', 'total_errors']].copy()
    df_mean_std.to_csv(dest_data_dir / '03_stress_test_mean_std.csv', index=False)

    # 4.4 04_stress_test_comparison.csv
    df_compare = df_agg_out[['strategy', 'avg_latency', 'p50', 'p95', 'p99', 'throughput', 'goodput', 'error_rate_mean', 'error_rate_pooled', 'total_errors']].copy()
    df_compare.to_csv(dest_data_dir / '04_stress_test_comparison.csv', index=False)

    # 4.5 05_stress_test_improvement.csv
    adaptive_row = df_compare[df_compare['strategy'] == 'adaptive_full']
    if adaptive_row.empty:
        raise ValueError("Cannot find adaptive_full strategy for improvement comparison.")
    adaptive_data = adaptive_row.iloc[0]

    baselines = ['least_connect', 'random', 'round_robin']
    imp_rows = []
    
    for base in baselines:
        base_row = df_compare[df_compare['strategy'] == base]
        if base_row.empty:
            continue
        base_data = base_row.iloc[0]

        def calc_imp(b, a):
            return ((b - a) / b * 100) if b > 0 else float('nan')

        imp_rows.append({
            'Baseline': base,
            'Avg Latency Diff (%)': calc_imp(base_data['avg_latency'], adaptive_data['avg_latency']),
            'P50 Diff (%)': calc_imp(base_data['p50'], adaptive_data['p50']),
            'P95 Diff (%)': calc_imp(base_data['p95'], adaptive_data['p95']),
            'P99 Diff (%)': calc_imp(base_data['p99'], adaptive_data['p99']),
            'Error Rate Diff (%)': calc_imp(base_data['error_rate_pooled'], adaptive_data['error_rate_pooled'])
        })
    
    df_imp = pd.DataFrame(imp_rows)
    df_imp.to_csv(dest_data_dir / '05_stress_test_improvement.csv', index=False)
    
    return df_run_out, df_compare, df_imp

def add_labels(ax, is_percent=False):
    for p in ax.patches:
        h = p.get_height()
        if h > 0:
            val_str = f"{h:.1f}%" if is_percent else f"{h:.1f}"
            ax.annotate(val_str, (p.get_x() + p.get_width() / 2., h),
                        ha='center', va='bottom', fontsize=9, xytext=(0, 3),
                        textcoords='offset points')

def generate_charts(df_run, df_compare, df_imp, charts_dir):
    sns.set_theme(style="whitegrid")
    STRATEGY_ORDER = ['adaptive_full', 'least_connect', 'random', 'round_robin']
    STRATEGY_LABELS = ['Adaptive', 'Least Connections', 'Random', 'Round Robin']
    COLOR_MAP = {
        'adaptive_full': '#2980B9',   # Blue
        'least_connect': '#27AE60',   # Green
        'random': '#F39C12',          # Orange
        'round_robin': '#E74C3C'      # Red
    }
    
    df_compare['strategy_label'] = df_compare['strategy'].map(dict(zip(STRATEGY_ORDER, STRATEGY_LABELS)))
    df_run['strategy_label'] = df_run['strategy'].map(dict(zip(STRATEGY_ORDER, STRATEGY_LABELS)))
    
    df_compare = df_compare.set_index('strategy').loc[STRATEGY_ORDER].reset_index()

    # Chart 01 - Average Latency
    plt.figure(figsize=(10, 6))
    ax = sns.barplot(data=df_compare, x='strategy_label', y='avg_latency', palette=COLOR_MAP.values())
    ax.set_title('Figure 01: Average Latency Comparison – Stress Test', fontsize=14, pad=15)
    ax.set_xlabel('Strategy', fontsize=12)
    ax.set_ylabel('Average Latency (ms)', fontsize=12)
    add_labels(ax)
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_01_avg_latency.png', dpi=300)
    plt.savefig(charts_dir / 'fig_01_avg_latency.svg', format='svg')
    plt.close()

    # Chart 02 - Tail Latency (Log Scale)
    plt.figure(figsize=(12, 7))
    df_melt_tail = df_compare.melt(id_vars=['strategy_label'], value_vars=['p50', 'p95', 'p99'],
                                   var_name='Percentile', value_name='Latency (ms)')
    df_melt_tail['Percentile'] = df_melt_tail['Percentile'].str.upper()
    
    ax = sns.barplot(data=df_melt_tail, x='strategy_label', y='Latency (ms)', hue='Percentile', palette='magma')
    ax.set_title('Figure 02: Tail Latency Comparison – Stress Test\n(Logarithmic Scale)', fontsize=14, pad=15)
    ax.set_xlabel('Strategy', fontsize=12)
    ax.set_ylabel('Latency (ms) - Log Scale', fontsize=12)
    ax.set_yscale("log")
    
    # Custom labels for log scale
    for p in ax.patches:
        h = p.get_height()
        if h > 0:
            ax.annotate(f"{h:.0f}", (p.get_x() + p.get_width() / 2., h),
                        ha='center', va='bottom', fontsize=8, xytext=(0, 3),
                        textcoords='offset points', rotation=90)
            
    plt.legend(title='Percentile')
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_02_tail_latency.png', dpi=300)
    plt.savefig(charts_dir / 'fig_02_tail_latency.svg', format='svg')
    plt.close()

    # Chart 03 - Error Rate
    plt.figure(figsize=(10, 6))
    ax = sns.barplot(data=df_compare, x='strategy_label', y='error_rate_pooled', palette=COLOR_MAP.values())
    ax.set_title('Figure 03: Error Rate Comparison – Stress Test', fontsize=14, pad=15)
    ax.set_xlabel('Strategy', fontsize=12)
    ax.set_ylabel('Pooled Error Rate (%)', fontsize=12)
    add_labels(ax, is_percent=True)
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_03_error_rate.png', dpi=300)
    plt.savefig(charts_dir / 'fig_03_error_rate.svg', format='svg')
    plt.close()

    # Chart 04 - Throughput and Goodput
    plt.figure(figsize=(12, 6))
    df_tpt = df_compare[['strategy_label', 'throughput', 'goodput']].copy()
    df_tpt_melt = df_tpt.melt(id_vars=['strategy_label'], var_name='Metric', value_name='RPS')
    df_tpt_melt['Metric'] = df_tpt_melt['Metric'].map({'throughput': 'Throughput (Total)', 'goodput': 'Goodput (Success)'})
    
    ax = sns.barplot(data=df_tpt_melt, x='strategy_label', y='RPS', hue='Metric', palette=['#95A5A6', '#3498DB'])
    ax.set_title('Figure 04: Throughput & Goodput Comparison – Stress Test', fontsize=14, pad=15)
    ax.set_xlabel('Strategy', fontsize=12)
    ax.set_ylabel('Requests per Second (RPS)', fontsize=12)
    add_labels(ax)
    plt.legend(title='')
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_04_throughput.png', dpi=300)
    plt.savefig(charts_dir / 'fig_04_throughput.svg', format='svg')
    plt.close()

    # Chart 05 - Stability (Mean +/- STD)
    plt.figure(figsize=(10, 6))
    # We plot the mean avg_latency and use seaborn pointplot or custom errorbar
    ax = sns.barplot(data=df_run, x='strategy_label', y='average_latency', errorbar='sd', capsize=.1, palette=COLOR_MAP.values())
    ax.set_title('Figure 05: Performance Stability Across Three Runs (Avg Latency)', fontsize=14, pad=15)
    ax.set_xlabel('Strategy', fontsize=12)
    ax.set_ylabel('Average Latency (ms) ± STD', fontsize=12)
    # Label means
    means = df_run.groupby('strategy_label')['average_latency'].mean()
    for i, p in enumerate(ax.patches):
        h = p.get_height()
        if h > 0:
            ax.annotate(f"{h:.1f}", (p.get_x() + p.get_width() / 2., h / 2),
                        ha='center', va='center', fontsize=11, fontweight='bold', color='white')
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_05_stability.png', dpi=300)
    plt.savefig(charts_dir / 'fig_05_stability.svg', format='svg')
    plt.close()

    # Chart 06 - Run-to-Run Variability Boxplot
    plt.figure(figsize=(10, 6))
    ax = sns.boxplot(data=df_run, x='strategy_label', y='average_latency', palette=COLOR_MAP.values(), width=0.5)
    sns.swarmplot(data=df_run, x='strategy_label', y='average_latency', color=".25", size=8)
    ax.set_title('Figure 06: Run-to-Run Latency Variability (12 Runs)', fontsize=14, pad=15)
    ax.set_xlabel('Strategy', fontsize=12)
    ax.set_ylabel('Average Latency (ms)', fontsize=12)
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_06_variability.png', dpi=300)
    plt.savefig(charts_dir / 'fig_06_variability.svg', format='svg')
    plt.close()

    # Chart 07 - Adaptive Performance Difference
    plt.figure(figsize=(12, 7))
    df_imp_melt = df_imp.melt(id_vars=['Baseline'], var_name='Metric', value_name='Difference (%)')
    # Filter only interested metrics
    interested_metrics = ['Avg Latency Diff (%)', 'P95 Diff (%)', 'P99 Diff (%)', 'Error Rate Diff (%)']
    df_imp_melt = df_imp_melt[df_imp_melt['Metric'].isin(interested_metrics)]
    
    # Map baseline names for UI
    base_map = {'least_connect': 'vs Least Connections', 'random': 'vs Random', 'round_robin': 'vs Round Robin'}
    df_imp_melt['Baseline'] = df_imp_melt['Baseline'].map(base_map)
    
    # Bar plot
    ax = sns.barplot(data=df_imp_melt, x='Metric', y='Difference (%)', hue='Baseline', palette='muted')
    
    # Color positive as green, negative as red
    for p in ax.patches:
        val = p.get_height()
        if not np.isnan(val):
            p.set_facecolor('#27AE60' if val >= 0 else '#E74C3C')
            
            # Label
            sign = '+' if val > 0 else ''
            va_align = 'bottom' if val >= 0 else 'top'
            offset = 3 if val >= 0 else -3
            ax.annotate(f"{sign}{val:.1f}%", (p.get_x() + p.get_width() / 2., val),
                        ha='center', va=va_align, fontsize=9, xytext=(0, offset),
                        textcoords='offset points', fontweight='bold')
    
    ax.set_title('Figure 07: Relative Performance Difference of Adaptive Load Balancer\n(Positive = Adaptive is Better | Negative = Adaptive is Worse)', fontsize=14, pad=15)
    ax.set_xlabel('Metric', fontsize=12)
    ax.set_ylabel('Relative Difference (%)', fontsize=12)
    ax.axhline(0, color='black', linewidth=1.2)
    
    # Custom legend to show meaning of colors instead of baselines (since colors encode value, not baseline)
    from matplotlib.patches import Patch
    legend_elements = [
        Patch(facecolor='#27AE60', label='Adaptive Better (+)'),
        Patch(facecolor='#E74C3C', label='Adaptive Worse (-)')
    ]
    plt.legend(handles=legend_elements, loc='upper right')
    
    plt.tight_layout()
    plt.savefig(charts_dir / 'fig_07_relative_difference.png', dpi=300)
    plt.savefig(charts_dir / 'fig_07_relative_difference.svg', format='svg')
    plt.close()

def main():
    script_dir = Path(__file__).resolve().parent
    base_out_dir = script_dir.parent
    # Data from previous step is located at:
    stress_test_root = script_dir.parent.parent
    src_data_dir = stress_test_root / 'data'
    
    if not src_data_dir.exists():
        print(f"Error: Cannot find source data at {src_data_dir}")
        return

    dest_data_dir, charts_dir = prepare_directories(base_out_dir)
    
    print("Generating CSV reports...")
    df_run, df_compare, df_imp = generate_csv_reports(src_data_dir, dest_data_dir)
    print(f"Created 5 CSV files in {dest_data_dir}")
    
    print("Generating 7 charts...")
    generate_charts(df_run, df_compare, df_imp, charts_dir)
    print(f"Created 7 charts in {charts_dir}")
    
    print("Done!")

if __name__ == '__main__':
    main()
