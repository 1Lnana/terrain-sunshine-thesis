# 复杂地形潜在日照时长计算：源码审查版本

本仓库用于毕业论文项目的代码和研究证据审查。更新：2026-11-02。

**当前状态：**保留原逐点遮挡、原16格分块及独立条带候选。条带仅完成最小人工正确性检查，尚未运行秦岭正式性能实验、尚未确立创新性。fixed1是一分钟条件参考，不是真值；已有时间采样最大18分钟误差不能由空间优化自动消除。

## 审查入口

1. [GPT审查任务](docs/GPT_REVIEW.md)：审查范围、关键问题与证据边界。
2. [实施路线](科研路线与进度.md)：T0～T4及当前计时／摊销计划。
3. [研究基线](毕业论文研究基线、实现目标与预期成果.md)：设计推导、文献比较和贡献边界。
4. [交接记录](阶段交接总结.md)：最新检查点置顶，其下为历史记录。

| 位置 | 内容 |
|---|---|
| src/ControlledVisibility.java | 原固定距离逐点遮挡查询 |
| src/BlockUpperBoundVisibility.java | 原整块最高高程筛选，保留16格对照 |
| src/StripUpperBoundVisibility.java | 独立条带候选，未接正式区域驱动 |
| src/StripVisibilityChecks.java | 三方人工正确性与方向统计检查 |
| src/ProjectedBaselineExperiment.java | 既有区域驱动，已有结果不代表条带性能 |
| configs/ | 既有配置，多数依赖未上传的本地DEM和user_runs路径 |
| scripts/run_strip_checks.ps1 | Windows隔离编译、人工检查及日志保存 |
| review_evidence/ | 精选原始记录的文本副本和来源／SHA-256清单 |

## 已有最小验证

2026-10-31人工检查：35条定向用例＋1,600条固定种子射线，14,859项断言通过；三方状态、首次停止距离与样点守恒一致，覆盖五种返回状态。人工额外跳过115段／413点，按16个22.5度方向箱计数。**这些不是实际DEM性能结果，也不构成对所有输入正确性的证明。**

证据见[最小检查目录](review_evidence/strip_checks_20261031_193851_3c31317c/)及[证据清单](review_evidence/manifest.json)。其他精选目录为历史已有实验摘要，本次上传没有重跑实验。

## 复现人工检查

Windows、JDK17，在仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run_strip_checks.ps1
```

默认JDK为原电脑路径，其他机器通过 `-Jdk "C:\path\to\jdk-17"` 替换为实际JDK根目录。Bypass仅本次子进程，不改变系统执行策略。每次输出新建于user_runs/，不会覆盖旧记录；不运行真实地形或性能实验。

其他平台可仅编译人工检查与Java标准库依赖：

```sh
mkdir -p build-review
javac -encoding UTF-8 -Xlint:all -d build-review src/AsciiGrid.java src/ZeroPolicy.java src/CoordinateContract.java src/AngleReference.java src/SearchConfig.java src/VisibilityResult.java src/VisibilityStatus.java src/RunStatistics.java src/ControlledVisibility.java src/BlockUpperBoundVisibility.java src/StripUpperBoundVisibility.java src/StripVisibilityChecks.java
java -cp build-review StripVisibilityChecks review-check-output
```

输出目录review-check-output必须不存在，已存在时更换新目录名。此跨平台命令仅供复现，本次上传未在其他平台验证。

## 上传范围与限制

包含现有Java源码、配置、脚本、研究文字和精选审查证据。约2.43GB原始数据、完整user_runs/、编译产物、展示图片及机器专属IDE设置不上传。旧user_runs路径指本地原件，由证据清单映射到精选副本；未精选的历史原始结果不能仅凭文字视为已独立复核。历史展示HTML和图片不在本审查快照。

[上传前README存档](docs/README_上传前存档.md)仅作历史资料，不能据旧任务恢复整套V1复现实验。
