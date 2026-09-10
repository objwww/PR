# 全量批 full-0910b 基线报告素材（EV-04 §6 口径）——首个 10/10 DECIDABLE 全链批

- 时间：2026-09-09 16:59:22 ~ 19:08:59 UTC（墙钟 **2h09m37s**，registry v2 预算内偏快侧）
- 启动器：`setsid nohup sh /tmp/cc-evalrun-glm5.sh deploy/alert/eval/eval-scenarios.yml full-0910b > /root/full-0910b.log 2>&1 &`（195）
- eval_run：`439f2cb2-5755-4068-8519-3324e44c2914`（SUCCEEDED）；registry=v2（S1/S2 resolved 窗 600→2100，digest 171a4d75…）；镜像 sha256:3f56530f1b65…（含 prev_episode_resolved 修复）
- 基线报告 digest：`790e6695682b7a29d376745088bfa2d17cea71e780546dbe78d920c49815548f`

## 一、四指标（eval_run 终态行 / 01 日志末行一致）

| 指标 | 值 |
|---|---|
| coverage（DECIDABLE 占比） | **1.0**（10/10） |
| conditional_accuracy（命中/DECIDABLE） | 0.0（0/10） |
| end_to_end_hit_rate | 0.0 |
| unresolved_rate | 0.0 |
| TP / FP / FN 总计 | **0 / 0 / 10** |

## 二、逐 Case 总表（02-db-after.txt §eval_case_result 原文）

| Case | verdict | hit | tp/fp/fn | latency_ms | rca_run_id（前 8 位） | reason |
|---|---|---|---|---|---|---|
| S1R1 | DECIDABLE | f | 0/0/1 | 722 | 56c660b2 | root_cause_miss_or_unresolved |
| S1R2 | DECIDABLE | f | 0/0/1 | 1011 | 3958b95b | 同上 |
| S2R1 | DECIDABLE | f | 0/0/1 | 1324 | a7ed14c9 | 同上 |
| S2R2 | DECIDABLE | f | 0/0/1 | 1098 | bf18e44c | 同上 |
| S3R1 | DECIDABLE | f | 0/0/1 | 1372 | 1b1dc875 | 同上 |
| S3R2 | DECIDABLE | f | 0/0/1 | 1778 | 65c57cb3 | 同上 |
| S4R1 | DECIDABLE | f | 0/0/1 | 2073 | e3cd78dd | 同上 |
| S4R2 | DECIDABLE | f | 0/0/1 | 446 | cff02915 | 同上 |
| S5R1 | DECIDABLE | f | 0/0/1 | 653 | df7f5110 | 同上 |
| S5R2 | DECIDABLE | f | 0/0/1 | 1262 | 2ab0a1ce | 同上 |

per-scenario 明细：S1~S5 各 2 Case，fn 各 2、tp/fp 各 0；**无 gate_blocked / run_not_found / prev_round_not_resolved / activate_failed**（对比 full-0910：gate_blocked 9）。所有 failure_sample 的 actualRootCause 均为 `unresolved / NO_CONFIRMED_ROOT_CAUSE`，actualSymptomCodes 空。

## 三、timing 对照（实测 vs registry timing；时间均为 UTC，源：02 §alert_event/§chaos_session/§rca_run）

firing 预算：S1/S2 max_firing_wait=1500s，S3~S5=300s；resolved 预算：S1/S2=2100s（v2），S3~S5=600s。"注入"时刻：S1/S2=flag 切换（无台账，取轮次边界推算值，标 ~）；S3~S5=chaos_session.created_at（精确）。"复位→Prom resolved"以案例行落库时刻近似下界（persist 在 deactivate 之后）。

| Case | 注入 | firing Δ（预算） | run 铸→终态 | 复位→Prom resolved（预算） | incident RESOLVED（AM 链延迟） |
|---|---|---|---|---|---|
| S1R1 | ~17:03:34 | 2m29s（1500s） | 17:06:33→34，0.7s | ~25m28s → 17:32:04（2100s） | 17:36:33（+4.5min） |
| S1R1 震荡 | — | 复位后 17:32:47→17:35:47 短促再 firing | — | 被轮前检查兜住（见下注） | — |
| S1R2 | ~17:36:52 | 3m11s（1500s） | 17:40:33→34，1.0s | ~3m28s → 17:44:03（2100s） | 17:45:33（+1.5min） |
| S2R1 | ~17:45:35 | **12s**（1500s） | 17:46:19→20，1.3s | ~23m26s → 18:09:48（2100s） | 18:11:18（+1.5min） |
| S2R2 | ~18:11:20 | 2m43s（1500s） | 18:14:34→35，1.1s | ~29m11s → 18:43:48（2100s） | 18:44:34（+47s） |
| S3R1 | 18:43:48 | 41s（300s） | 18:45:00→01，1.4s | ~26s → 18:45:29（600s） | 18:50:00（+4.5min） |
| S3R2 | 18:50:01 | 28s（300s） | 18:51:00→02，1.8s | ~26s → 18:51:30（600s） | 18:56:00（+4.5min） |
| S4R1 | 18:51:30 | 44s（300s） | 18:52:44→46，2.1s | ~40s → 18:53:13（600s） | 18:57:44（+4.5min） |
| S4R2 | 18:57:45 | 29s（300s） | 18:58:44→45，0.4s | ~28s → 18:59:15（600s） | 19:03:45（+4.5min） |
| S5R1 | 18:59:15 | 89s（300s） | 19:01:15→15，0.7s | ~60s → 19:01:44（600s） | 19:06:15（+4.5min） |
| S5R2 | 19:06:16 | 103s（300s） | 19:08:29→30，1.3s | ~58s → 19:08:59（600s） | 19:13:29（+4.5min） |

注（重要实测发现）：
1. **S1R1/S1R2 复位后各有一次阈值震荡再 firing**（17:32:47→17:35:47、17:44:47→17:45:02，30m 窗临界抖动）——发生在门已开（Prometheus 首清）之后、incident RESOLVED 之前。轮前 incident RESOLVED 检查（prev_episode_resolved）正好兜住这个竞态：R2 等到 incident 落 RESOLVED 才注入，若只有门检查，R2 将带着震荡残留注入重蹈 full-0910 覆辙。修复在真实栈上抓到设计外的活样本。
2. S1 firing 实测 2.5~3.2min，远快于 registry 注释的 M3-30 校准值 ~17.3min——当前栈 6h 窗有历史错误存量，烧损基线偏热（full-0910 README §根因 5 同记录）。
3. S2R1（paymentUnreachable=100% 不可达）firing 仅 12s——即烧即超；S2R2 恢复窗 29m11s 逼近 2100s 预算（余量 ~6min），后续批次若 6h 窗更热有超预算风险，列入观察项。
4. AM 链延迟（Prom resolved → incident RESOLVED）稳定在 ~4.5min（S1R2/S2 两次较快因撞上震荡合并），轮前等待预算 600s（S3~S5）/2100s（S1/S2）均覆盖。

## 四、usage 出账（03-usage-ledger.json 原文）

`state=UNMATCHED`，10 个 attempt 全部 `no_rows_under_run_key`，expected_prompt/completion=0，observed=null；proxy 侧取到 280 行但 run_key_alias=glm5-baseline-0909 下零行。**符合 NATIVE 零模型调用预期**：10 个 rca_run 全部 engine=NATIVE（02 §rca_run），Native 确定性执行器不经 LLM，litellm 下无账可出——UNMATCHED 是如实口径而非出账故障。预算执行面 PROXY_VIRTUAL_KEY 在位。

## 五、与旧 0/47 基线的差异归因（北极星指标对账）

旧基线：AM3 G2 记录评测真实命中率 0/47。本批：0/10（fn=10）。**两批在三个维度同时不同，单因子贡献无法分离，逐项诚实标注**：

| 因子 | 本批状态 | 对命中率差异的贡献 |
|---|---|---|
| 管线结构（评测编排/AM 通知链/registry） | 本批 10/10 全链走通（coverage 1.0）；0/47 时代结构面有大面积 TIMEOUT/缺席 | **可认定主贡献于"可测性"**：coverage 0→1.0 由管线修复（AM 链修复 + prev_episode_resolved + registry v2）解释；但对"命中"贡献为 0（命中面没动） |
| Holmes 退场（M6-07 第二引擎物理下线） | 本批无 Holmes 运行，全 NATIVE | **UNKNOWN**：无同条件 Holmes 对照跑，无法量化 Holmes 若在环的命中差 |
| glm-5 模型 | 本批 0 次模型调用（usage UNMATCHED） | **0 次调用 = 未测量，非"零贡献"**：glm-5 对本批命中率的贡献 UNKNOWN；0 命中的直接原因是 NATIVE 执行器不做 LLM 推理、如实报 UNKNOWN |

结论：**fn=10 = Native 零命中基线原点**——coverage=1.0 证明"测量仪"已可信（每 Case 都是真实现场→incident→RCA→报告→评分的完整证据链），e2e=0 是 NATIVE 确定性执行器（零 LLM）的诚实读数。LLM 在环状态：本批**不在环**（0 调用）；向"LLM 可信上场"的收敛 = 评测管线本身已能无结构噪声地区分"命中/未命中"，下一棒是把 LLM 引擎放回环内重测同 registry。

## 六、现场状态（批后 19:21 UTC 核查）

- flagd：paymentFailure=off、paymentUnreachable=off ✓
- Prometheus firing 告警：0 ✓；incident 评测面（checkout/Arena×3）全 RESOLVED ✓
- chaos 会话：批内 6 个全 CLOSED，non-CLOSED 总数=0 ✓
- eval-runner-full-0910b 容器已随 `--rm` 退出 ✓
- 195 资源：mem avail 3G / 磁盘剩 25G；全程未重启任何容器 ✓
- 已知残留（非本批产生）：incident 表 48 行 FIRING 为 M6 线 am6e2e* 历史 episode（resolved webhook 未达的陈旧行），与评测面零交集，未动

## 证据目录

- `01-eval-run.log`：批日志全文（34 行；含四指标末行 + usage 对账行）
- `02-db-after.txt`：eval_run 终态行 / 10 Case 逐行 / alert_event 21 条（全 episode 时间线）/ rca_run 10 行 / chaos_session 6 行 / incident 终态
- `03-usage-ledger.json`：usage 对账完整 JSON（10 attempt 全 UNMATCHED）
- 195 原件：/root/full-0910b.log、/root/full-0910b-db-after.txt、/root/full-0910b-db-fix.txt
