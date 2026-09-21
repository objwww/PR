# Jev 标注与规则臂基线（JE-B01）

更新：2026-09-20。本目录新增 `rules_arm.py`（规则臂基线）与本说明；**未改动已冻结的 A/B 实验管线**（lab.py / app.js 原样——其 README §6 纪律：新臂不塞进已冻结版本）。

## 1. rules_arm 是什么、不是什么

`select_rules(case, opts)` 是**零模型费用的确定性选材基线**：必保留钉窗 → 行模板去重（uuid/hex/数字归一，同模板保首条）→ 错误关键词优先 → 原序补足 → 字符预算截断。它回答：

> 不用 Jev，纯规则能从同一冻结证据池选回多少 gold 相关证据？

在下一版实验（A=现有窗口 / **R=规则臂** / J=Jev 臂）中，R 是证明"Jev 本身是否有增量"的必要对照——没有 R，J 对 A 的提升分不清是 Jev 的功劳还是"任何选材都能拿到"的功劳。

**它不是**：线上裁剪器、Jev 的替代品、或已接入实验台的臂。接入 run_arm/summarize/配对统计属于下一版实验的改动（需新的实验版本号，不复用 A/B 冻结命名）。

## 2. 运行测试

```powershell
python -m unittest discover -s experiments\jev-lab -p test_rules_arm.py -v
```

8 项用例：必保留恒入选、同模板去重保首条、条数/字符预算、关键词优先、必保留超预算在任何 API 之前拒绝、确定性、归一化锚。

## 3. 下一版接入清单（供实施者，非本次改动）

1. `lab.py` 的 `run_arm()` 增加 `arm == "rules"` 分支：`selected = rules_arm.select_rules(c, opts)`，无 provider Jev 调用、calls 只含 llm。
2. 实验 options 增加 `arms: ["base","rules","jev"]` 声明（缺省仍双臂，旧实验 JSON 不受影响）；配对键从 `p["jev"]` 泛化为逐臂。
3. `summarize()`/`paired_ci()` 按臂参数化；R vs J 用与 A vs B 相同的成对规则与 ≥5 独立事件门槛。
4. UI 下拉与图表按臂枚举渲染；导出 JSON 含臂声明。
5. 版本号递增：新实验存新文件，不复用已冻结 A/B 的实验版本语义。

## 4. 人工标注（gold）操作规范（README §3 的执行版）

数据集结构以 [example-dataset.json](example-dataset.json) 为准；导出/标注按以下纪律：

| 步骤 | 纪律 |
|---|---|
| 取材 | 从已有导出结果或只读快照取证据，禁止用会重新投递告警的评测驱动；每案可见输入 ≤24KB |
| 冻结 | 候选池按基线阅读顺序排列；含早期关键证据、重复日志、噪声、反证、中文/混合文本 |
| `required` 标记 | **在看实验结果之前**标（关键反证、不可丢弃边界）；禁止根据 gold 反推 |
| `gold.symptom_codes` | 人工确认的完整症状集合，禁止用模型预测代替；告警名取值 |
| `gold.evidence_ids` | 必须引用候选池现有 ID；相关性=对目标/反证/约束/因果变化有用 |
| `gold.root_cause` | 规范三元组逐字取自 root_cause_catalog 同一行；证据不足写 UNRESOLVED |
| 复核 | 两名标注者独立标注，分歧案例先解决再入库；`cluster_id` 同一事故共享（独立事件数 ≥5 才出 CI） |
| 脱敏 | 标注数据将发给供应商（LIVE 时），按数据权限保管导出 JSON |

## 5. 主链（control-app）侧的对应关系

Java 主链已实现同构的保护纪律：`JevEnhancementService.protectedRefs`（绑定承诺 ∪ 工作记忆反证恒入窗）与本模块"必保留恒入选"同律；`ContextAssembler.EvidenceSelection` 应用面与本模块"原序保持、不重排"同律。实验台 R/J 对 A 的实测结论可直接迁移为主链 `SELECT` 模式的放行依据。
