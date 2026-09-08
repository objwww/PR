# 告警 AM6 — Holmes Shadow 差异报告模板（M6-05）

> 模板用途：M6-05「Holmes 只读对照期（反向影子）」的周期性差异报告。每个报告窗
> （建议与 canary 晋升窗同粒度）按本模板填一册，结论直接供 **M6-06 Holmes 退场
> 决策记录** 取数。填写纪律：**只填可追溯的真值**（SQL 随件），无数据的格子写
> 「无数据」，禁止脚本补数（执行纪律 4）；本报告不判"谁正确"——无 GT 只记
> disagreement（架构 v1.2 :736/739）。

- 报告窗：`<UTC 起始> ~ <UTC 结束>`（批次号：`<batch-id>`）
- 填写人 / 日期：`<operator>` / `<YYYY-MM-DD>`
- 部署事实锚：control-app 镜像 digest `<sha256:...>`；active bundle digest `<sha256:...>`（percent=`<n>`）；V34 work 面行数锚见 §5

---

## 1. 结论速览（供 M6-06 引用）

| 项 | 值 | 取数 SQL |
|---|---|---|
| 对照期 NATIVE 生产 run 总数 | `<n>` | `SELECT count(*) FROM rca_run r JOIN ... 路由 NATIVE AND 终态 SUCCEEDED AND finished_at IN 窗` |
| 抽样入队对照工作行数（COMPARISON） | `<n>` | `SELECT count(*) FROM holmes_shadow_work WHERE kind='COMPARISON' AND created_at IN 窗` |
| 影子执行成功（工作行 SUCCEEDED） | `<n>` | `... AND state='SUCCEEDED'` |
| V32 对照行落账数 | `<n>` | `SELECT count(*) FROM engine_comparison WHERE shadow_exec_ref='holmes-shadow-worker'` |
| 含 disagreement 的对照行 | `<n>`（占比 `<pct>%`） | `disagree_flags != '[]'` |
| 底噪校准行（noise_baseline 非空） | `<n>`；其中 `disagree=true` `<n>` | `noise_baseline IS NOT NULL` |
| 工作行 FAILED/EXHAUSTED | `<n>` / `<n>` | `... state IN ('FAILED','EXHAUSTED')` |

**一句话结论**（人工誊写，禁止模板预填）：`<对照期差异面是否超出底噪、是否支持进入 M6-06 退场预演>`。

---

## 2. 成本收益对照（绝对 SLO 面，E-20 禁 before/after）

> 数据源：`rca_attempt`（影子链 attempt 行带 sampling fingerprint）+
> `external_invocation_ledger`（run_id = 影子 run id，token/latency 真值）+
> `engine_comparison.cost_compare`。双侧各自对照**绝对**阈值，不做 inter-period 对比。

| 维度 | NATIVE 生产侧（claims 面 run） | Holmes 影子侧（V34 锚 run） | 绝对阈值 | 判定 |
|---|---|---|---|---|
| 单次调查 latency P50/P95 | `<ms>` / `<ms>` | `<ms>` / `<ms>` | `<slo>` | `<达标/超>` |
| 单次调查 tokens（prompt/completion/total） | `<n/a or ledger>` / `<n/a>` / `<n/a>` | `<n>` / `<n>` / `<n>` | `<budget>` | `<达标/超>` |
| 每成功对照成本（holmes 影子单跑摊本） | — | `<tokens 或 元>` | `<cap>` | `<达标/超>` |
| 对照期影子总花费占 canary 预算比 | — | `<pct>%` | ≤ `<cap>%` | `<达标/超>` |

（费用换算口径与单价表：`<来源>`；无单价数据时本节留 tokens 绝对值，不臆造金额。）

---

## 3. disagreement 分类台账（六维，无 GT 不判对错）

> 数据源：`engine_comparison.disagree_flags`（`[{dim, holmes, native}]` 数组）。
> 每维一节：计数 + 3 例摘录（run_id 对 + 双侧原值）。**分类口径**：
> - `result`：根因三元组或 claim 键集不同 → 逐条标注「同根异因 / 异根 / 键集漂移」；
> - `latency` / `cost`：数值差超报告窗观察带宽（带宽取值：`<p50±k·MAD>`）才算异常；
> - `tool_legality` / `safety_violation`：M6-05 worker 面缺逐 run 观察面，**恒缺数不标记**——本节只有「无数据」一种合法状态，出现数据即事故（见 BUGLOG 接续编号上报）。

| 维度 | 标记行数 | 分类计数 | 典型例（native_run_id / holmes_run_id / 双侧值） |
|---|---|---|---|
| result | `<n>` | `<同根异因:a, 异根:b, 键集漂移:c>` | `<...>` |
| schema | `<n>` | `<...>` | `<...>` |
| latency | `<n>` | `<...>` | `<...>` |
| cost | `<n>` | `<...>` | `<...>` |
| tool_legality | 无数据（缺观察面） | — | — |
| safety_violation | 无数据（缺观察面） | — | — |

---

## 4. 底噪校准（同 snapshot Holmes control-vs-control）

> 数据源：`engine_comparison WHERE noise_baseline IS NOT NULL`（V34 CALIBRATION
> 工作行产物）。**判读规则**：某维 disagreement 计数 ≤ 底噪同维计数（按窗内比例）
> ⇒ 该维差异可归为引擎非确定性噪声；超出 ⇒ 真差异候选，进入 M6-06 证据清单。
> 底噪之外的差异仍须过 §2 绝对 SLO（差异−底噪≠合格，落码方案 M6-05 节）。

| 维度 | 对照期 disagreement 计数（§3） | 底噪计数 / 底噪率 | 判读 |
|---|---|---|---|
| result | `<n>` | `<n>` / `<pct>%` | `<噪声 / 真差异候选>` |
| latency | `<n>` | `<n>` / `<pct>%` | `<...>` |
| cost | `<n>` | `<n>` / `<pct>%` | `<...>` |
| schema | `<n>` | `<n>` / `<pct>%` | `<...>` |

校准样本量与局限（必填）：底噪行数 `<n>`；底噪为 holmes-vs-holmes 同模型自比，
**不含模型代际漂移**；样本 `<n>` 不足以分辨 `<pct>%` 以下差异时显式声明。

---

## 5. 工作面健康度（V34 运行证据）

| 项 | 值 | 备注 |
|---|---|---|
| EXHAUSTED 工作行（有界重试耗尽） | `<n>` | `last_error` 分布：`<TIMEOUT:x, EXECUTOR_*>` |
| 租约过期回收（epoch>1 的 SUCCEEDED 行） | `<n>` | 过高 ⇒ lease 配置 &lt; 真实在途窗，需调 `APP_ALERT_SHADOW_HOLMES_LEASE` |
| INCIDENT_ACTIVE_HOLMES_RUN 诚实失败 | `<n>` | 与 fallback 铸造窗重叠次数对账 |
| metrics：`rca_holmes_shadow_sample_total` | `<截屏/值>` | outcome 分桶 |
| metrics：`rca_holmes_shadow_work_total` | `<截屏/值>` | outcome 分桶 |

---

## 6. 给 M6-06 的移交清单

- [ ] §1 速览数字与 `docs/测试证据/AM6/<batch>/` 证据包 sha256 对拍一致；
- [ ] §4 底噪判读完成：真差异候选清单（native_run_id 列表）已附录；
- [ ] §2 绝对 SLO 双达标（latency/cost 均在阈值内）或超阈值项已显式列出；
- [ ] 未决项/开放项（O 表接续编号）已登记；
- [ ] 本报告已入 `docs/`，BUGLOG/PROGRESS 台账已按编号接续。

**附：本报告 SQL 原文**（粘贴随件、保证可复核；每条 SQL 与 §1~§5 的格位一一对应）：

~~~sql
<-- 逐条粘贴取数 SQL -->
~~~
