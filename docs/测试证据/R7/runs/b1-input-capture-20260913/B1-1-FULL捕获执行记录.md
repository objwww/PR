# B1-1 输入捕获 FULL 档真跑执行记录（R2/V90 + CL-03 入模实证）

- 日期：2026-09-13 02:53~02:59Z（UTC）；执行者：B1 执行者（真机 195）
- 适用构建：jar md5 `fd546d26eae74689d94c37a879e86787`（V99~V108 批部署，与 CL-OP-OR 台账同基线；本窗仅 env 覆写，jar 未动）
- 目标：①R2/V90 FULL 档机制在真机端到端成立（原文落库+digest 对账）；②CL-03 分型投影在**活 prompt 全文**中断言（A13-01 半面收口）；③RR16 模型输入面秘密扫描（真集合）。

## 一、机制与窗口

- env 覆写走 compose override 文件（`b1-fullcap-override.yml`：`services.control-app.environment.APP_ALERT_R7_INPUTCAPTURE: "full"`），`docker compose -p deploy -f base -f override up -d control-app` 重建后 env 验证 `APP_ALERT_R7_INPUTCAPTURE=full` 在容器内生效。教训（对应不确定项 M/BA-137）：`.env` 键必须经 compose environment 映射行才进容器——两度被对方重建窗冲掉后改走 override 才稳定。
- 窗口纪律：02:50Z 三面侦测（新 run/容器重启/文件改动）确认静默后才重建；主事件结束后（02:58Z）再次侦测（近 15 分钟唯一 run=本次 6b607ec9、树无改动）才复原。
- 故障注入：flagd `paymentFailure=100%`（S1/S2 侧车），与 run26 同配方（qwen3-max-preview、prompt v13、零委派）。

## 二、主事件

- A0 驱动（树外 e2e 副本+CRLF 修复）：phase1 姿态（health 200+bundle 发布激活 digest 83f31718…）→ phase2 合成告警注入（原子对成立）→ **run `6b607ec9-4b8d-4062-95c0-cc7d307911cd`** → phase3 **SUCCEEDED** → phase4 主链 PASS → phase5 直查面 PASS（allowlist 全落；证据 3 行；TRUE Claim 引用全锚定）→ phase6 回执链 PASS（**5 笔调用全 SUCCESS；"捕获恰一行+digest 对账一致（R2）"由 A0 脚本自身断言**）→ phase7 FAIL＝已知 BA-134 脚本两雷（package 列名+publication-loser 假设），系统无缺陷。
- 捕获行：`rca_model_input` join `rca_model_call`（role=primary）恰 5 行，全部 `capture_level=FULL`，`prompt_text` 5598 / 6281 / 14934 / 15322 / 15610 字节（seq 0~4；seq0=首次派发尚无证据面，seq1 起带证据投影）。

## 三、断言（b1-fullcap-verify2.py，195 python3.6 兼容版）

解析注意：prompt_text 含原生换行，psql 须 `-F '\x1f' -R '\x1e'`（字段/记录分隔），按行 split 会撕裂记录——首版断言器零行假象即此（另修 capture_output→Popen+PIPE）。

| 断言组 | 结果 |
|---|---|
| FULL 档原文在库（5/5 行 level=FULL 且 text 非空） | PASS |
| digest 完整性：sha256(prompt_text)==prompt_digest 逐行 | **5/5 PASS** |
| 信封面：`valid_artifact_refs` 在 prompt | 5/5 PASS |
| CL-03 投影标记：`"observations"`/`"message"`/`"labels"`/`"window"`/`"total_count"` | 汇总全 True（seq2+ logs+metrics 双面） |
| 旧折叠残留：`"(+N struct fields)"` | 零残留 |
| 真实证据语义：Payment / error_type / payment 词形 | 5/5 PASS |
| DB CHECK 双向钉（FULL⇒text 非空 / DIGEST_ONLY⇒空） | schema 侧在位（`\d rca_model_input` 约束原文留档） |

投影活样本（末轮 prompt 截取，见 verify2-output.txt）：`logs.aggregate` → `observations:[{at,service,message}] + total_count/shown_count`；`metrics.catalog` → 26 行只展 6 条 + `"truncated":true,"omission_reason":"ITEM_LIMIT"`（OBSERVATION_LIMIT=6 截断留痕）。附带观察：logs.aggregate 的 observation message 为空串——聚合执行器载荷本就只带计数不带行（BA-133 语义族），投影如实渲染非 CL-03 缺陷。

## 四、RR16 模型输入面（真集合）

- 首跑为空集合假过（prompt-*.txt 未写出）——修断言器后重扫：5 文件（5.5~15.6KB×5，共 57760B 原始 TSV）**秘密出现总数 0**（`RR16-PROMPTSCAN-PASS`）。
- 证据包红线复核：容器 env dump 曾含 6 敏感键+3 裸 hex 机器 Bearer（operator/release/duty 线）——全部打码后终扫 `FINAL-SCAN-PASS`（sha256 摘要命中属正常，仅报备）。

## 五、复原（02:59Z）

- `b1-fullcap-restore.sh`：摘 override 重建 control-app → startup-ok → **INPUTCAPTURE 已回默认 digest-only**（容器 env 复核）→ flagd `paymentFailure` 100%→**off**。容器态快照+打码 env 留档（restore-container-state/env.txt）。

## 六、证据清单（本目录）

- `prompt-0..4.txt`：5 条 FULL 原文（断言器从 DB 写出）；`prompts-raw.tsv`：原始 dump（57760B）
- `verify2-output.txt`：断言全输出（ASSERT-OVERALL: PASS）；`rr16-promptscan-output.txt`
- `a0/b1-fullcap-a0.log` + `a0/a0-20260913T025432Z/`：A0 驱动全 phase 工件（phase4-7 探针、bundle/activate/publish resp 等）
- `scripts/`：override yml+prep/main/verify/restore 脚本+verify2.py+promptscan（可复现链）
- `run-id.txt`、`restore-container-state.txt`、`restore-container-env.txt`（打码版）

## 七、结论

- **R2/V90 FULL 档机制 VERIFIED_TARGET**：原文落库、digest=原文 sha256 逐行对账、A0 账面双断言、复原回默认全链绿。
- **CL-03 升 VERIFIED_TARGET**：分型投影在真模型活 prompt 全文断言通过，旧折叠零残留（A13-01"证据投影丢失"半面收口；真模型对照残留留 MC01）。
- **RR16 两面全收**；**不确定项 N 关闭**。
- 遗留：phase7=BA-134（脚本资产雷，待修复窗）；logs.aggregate 空载荷语义=BA-133（已立案）。
