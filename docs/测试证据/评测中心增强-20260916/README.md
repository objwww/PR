# 3.11 评测中心增强（治理打标 + 数据集分层） — 测试截图文档

- 批次：前端产品化 · Wave 5（方案 v1 §3.11；定时自驱循环第 5 轮）
- 日期：2026-09-16
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道访问）
- 路由：`/eval/datasets`（数据集分层）、`/eval/runs`（实验治理）
- 新后端：`GET/POST /api/eval/governance/dataset-tiers`、`GET /api/eval/governance/run-tags`、`POST /api/eval/runs/{runId}/governance-tag`；新迁移 **V133**（eval_run 治理三列 + eval_dataset_tier 表）+ **V134**（列级 UPDATE 补授权）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| 方案 v1 §3.11 分层评测纪律 / RCA-100 口径 | 评测集按用途分层（冒烟阻塞发版、回归测漂移、探索看分布、红队找边界） | 数据集分层标签四值（冒烟/回归/探索/红队），`eval_dataset_tier` 表持久化，数据集页就地打标 |
| 美团评测四层 / 阿里云持续 eval | 实验有治理生命周期，废批不污染质量口径 | 实验治理标签四值（有效/废批·超时/废批·其他/归档）+ 打标人/时间落档（账本纪律） |
| Langfuse/Helicone 实验管理 | 一键复制配置重跑，幂等键防重 | 「一键重跑」按钮：复用既有 `POST /api/eval/runs`（携带原实验 datasetVersion/model/promptVersion + 新幂等键），受理后返回新 runId |
| 方案 §3.11 持续回流 | SUCCEEDED run → 候选案例 → 人工评审准入 | **既有能力确认**：`/api/eval/regression-candidates` 全链已在（候选/清单/评审/materialize 准入），本批核对未重复建设 |

**只可多不可少**：本批为治理面增强（分层+废批+重跑+回流确认）。三维评分（实体一致性/调查路径/结论复核）与 LLM 评审评分器属新增评分引擎，需评审后建评分器与迁移，本批不做（不造假，README 明示边界）。

## 二、截图清单

| # | 文件 | 页签/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 56 | 56-评测中心-数据集分层打标.png | 数据集表新增「分层」列（冒烟/回归/探索/红队下拉，就地打标） | `GET/POST /api/eval/governance/dataset-tiers` | demo/v1→SMOKE 落库，读回一致 ✔ |
| 57 | 57-评测中心-实验治理列与重跑.png | 实验表新增「治理标签（废批）」列（47648add 显示"有效"）+ 每行「一键重跑」按钮 | `GET /api/eval/governance/run-tags` + `POST .../governance-tag` + 既有 `POST /api/eval/runs` | 已打标实验=1 与 SQL 一致 ✔ |

## 三、SQL 对账（2026-09-16 真机）

```sql
select count(*) from eval_run where governance_tag is not null;   -- 1  = 已打标实验
select count(*) from eval_dataset_tier;                           -- 1  = 已打标数据集
select version from flyway_schema_history
 where success order by installed_rank desc limit 1;              -- 134（V133+V134 均成功）
```

## 四、过程中的授权修复（V134，如实记录）

V10 给 control_app 的 eval_run UPDATE 是**列级授权**（仅引擎列 state/计数等）；V133 新增的
`governance_tag/governance_tagged_by/governance_tagged_at` 三列不在授权列集——打标 UPDATE
报 permission denied，经 /error 转发被 `denyAll` 映射成 403（与 3.4 批修复的掩蔽同款）。
V134 按列追加授权（不动 V10 既有面），打标链路复验通过。教训：新增列级写面必须随迁移补授权。

## 五、验收（方案 §6 逐项）

1. 零错误数据：打标读回与 SQL 一致；重跑走既有幂等发起面（幂等键防重）✔
2. 零裸枚举：治理标签/分层经 `zh.js`（GOV_TAG_ZH/TIER_ZH）中文化上屏 ✔
3. 零可点必失败：RUNNING 实验重跑按钮禁用；打标被拒展示后端中文原因 ✔
4. 空态即引导：未打标实验显示"打标"占位、治理面缺席时如实留空 ✔
5. 迁移 V133/V134 成功、flyway=134 ✔；权限：写面 OPERATOR 会话 + 列级授权收口 ✔

## 六、遗留（如实记录）

- **LLM 评审评分器**与**三维评分**（实体一致性/调查路径/结论复核，RCA-100 口径）为新增评分引擎——需评分器设计与迁移（评分落列），架构影响大于一轮循环，待专项评审。
- 红队集自动来源（注入扫描特征库 + Guardian UNSAFE 用例自动成集）待注入扫描特征库成表后接线。
