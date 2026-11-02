"""读取同一批历史结果，绘制误差—耗时图；不运行日照算法。"""

import csv
from datetime import datetime
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (
    ROOT.parent / "毕业论文_历史资料" / "2026-10-21_整理前"
    / "runs" / "adaptive_time_v1" / "summary.csv"
)


def main():
    # CSV每一行对应一种方法，时间和误差都取自这一批实验。
    with SOURCE.open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))

    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False
    fig, ax = plt.subplots(figsize=(10, 6))

    for prefix, color, marker, label in [
        ("fixed_", "#377eb8", "o", "固定步长"),
        ("adaptive_", "#d95f02", "^", "自适应V1"),
    ]:
        group = [row for row in rows if row["method"].startswith(prefix)]
        ax.scatter(
            [float(row["runtime_sec"]) for row in group],
            [float(row["MAE_min"]) for row in group],
            color=color, marker=marker, s=75, label=label, zorder=3,
        )

    # 将两个相近的V1标签分开，用引线指向各自数据点。
    offsets = {
        "fixed_1min": (-95, 15),
        "fixed_5min": (15, 8),
        "fixed_10min": (15, 8),
        "fixed_20min": (15, 8),
        "fixed_30min": (15, 8),
        "adaptive_20min": (60, 25),
        "adaptive_30min": (35, -32),
    }
    for row in rows:
        method = row["method"]
        label = method.replace("fixed_", "固定 ").replace("adaptive_", "V1 ")
        label = label.replace("min", "分钟")
        ax.annotate(
            label,
            (float(row["runtime_sec"]), float(row["MAE_min"])),
            xytext=offsets.get(method, (12, 10)), textcoords="offset points",
            fontsize=10, arrowprops={"arrowstyle": "-", "color": "#777777"},
        )

    ax.set_xlabel("计算耗时（秒；三次正式运行的中位数）")
    ax.set_ylabel("相对固定1分钟参考的MAE（分钟）")
    ax.set_title("固定步长与V1：误差—耗时权衡")
    ax.set_xlim(0, max(float(row["runtime_sec"]) for row in rows) * 1.1)
    ax.set_ylim(-1.3, max(float(row["MAE_min"]) for row in rows) * 1.12)
    ax.axhline(0, color="#aaaaaa", linewidth=0.7)
    ax.grid(alpha=0.2)
    ax.legend(loc="upper right")
    fig.text(
        0.5, 0.025,
        "历史首轮实验：32×32接收区，2023-06-21；仅使用adaptive_time_v1同批次汇总。\n"
        "本图为历史数据整理，不是新实验；计算耗时不含DEM加载及CSV写出。",
        ha="center", fontsize=9, color="#555555",
    )
    fig.tight_layout(rect=(0, 0.09, 1, 1))

    # 每次新建目录，保留之前生成的图。
    output = ROOT / "user_runs" / datetime.now().strftime("baseline_plot_%Y%m%d_%H%M%S_%f")
    output.mkdir(parents=True, exist_ok=False)
    target = output / "v1_error_runtime.png"
    fig.savefig(target, dpi=180)
    plt.close(fig)
    print(f"SOURCE={SOURCE}")
    print(f"PLOT_SAVED={target}")
    print(f"METHODS={len(rows)}")


if __name__ == "__main__":
    main()
