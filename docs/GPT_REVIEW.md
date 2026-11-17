# 给 GPT 的阅读与回答要求

快照日期：2026-11-17。先读 [CURRENT_STATUS](CURRENT_STATUS_2026-11-17.md)，再按问题读取[方法](METHODS_AND_RESULTS_2026-11-17.md)、[实验表格](EXPERIMENT_RESULTS_2026-11-17.md)、[常见疑问](RESEARCH_QA.md)、[文献核查](LITERATURE_CHECK.md)及相应源码/原记录。

## 可以直接复制到新对话的提示词

> 请读取这个仓库的 README.md、docs/GPT_REVIEW.md 和 docs/CURRENT_STATUS_2026-11-17.md，再根据我的问题阅读相关方法、源码及 review_evidence/2026-11-17 下的原记录。用中文直接回答。区分源码已实现、当前记录实测、假设、未完成工作。涉及数字时注明实验批次、时间策略、计时范围和误差；不要把不同批次提速相乘，也不要把 fixed1 当物理真值。不要把历史待办当当前任务。我的问题是：……

## 源码定位

|问题|优先源码|
|---|---|
|原时间方法与二分细化|BinaryLocalRefinement.java；时间对照驱动|
|纯双端及不同日照结构|TimeStructureSearch.java；QinlingTwoBoundaryExperiment.java|
|完整双端＋暗时回退|LimitedDarkBinaryRefinement.java；TimeSearchPairedBenchmark.java；QinlingTimeSearchPairedBenchmark.java|
|原逐点判定与16格上界|ControlledVisibility.java；Block16DirectionVisibility.java；BlockUpperBoundVisibility.java及其实际引用类|
|安全后缀与四组组合|SuffixBlockVisibility.java；SuffixBlockVisibilityNoStats.java；SuffixCombinationExperiment.java及同目录实际驱动|
|多日地平线复用|ProjectedMultiDayExperiment.java及 Horizon 相关类|

表中名称是检索入口，不替代调用链核查。当前源码快照可能晚于某历史批次，严格复现应结合原记录中的冻结指纹，不能声称当前源码必与每次历史运行逐字一致。

## 回答约束

1. “已组合”以11月11日四组记录为准；历史基线11月10日的“尚未组合”不再适用。
2. “>1分钟点数”“日总MAX”“早边界MAX”是不同指标，不能把57分钟说成日总最大误差。
3. 所有成本都要计入实际探针和回退，不能用参考序列免费选算法。
4. 16×16是遮挡地形的辅助上界索引，不是把256个目标合成一个。完整性、插值支撑和数值保护是保持原离散结果的条件。
5. 插值减少比例不等于时间减少比例；索引载荷不等于程序峰值内存。
6. 地形名称不能证明一天只有一段日照。中点亮也不能证明中间没有再次遮挡。
7. 后缀/分块工程收益不能直接认定核心原创性；不凭方法数量承诺毕业结果。
8. 文献身份仍待老师确认；专利包含日中阴影检查，不能说它只是“两端相减”。没有完整专利实现对照，不判断谁客观更快。
9. 仓库没有原DEM和完整大型节点表。不能读取或证据缺失时明确说明，不捏造复现实验或结论。

[精选证据索引](../review_evidence/2026-11-17/README.md)。本轮仅整理和上传，没有新科学计算。
