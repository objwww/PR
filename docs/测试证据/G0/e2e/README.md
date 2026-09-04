# G0-10 单链路 E2E 证据包（195 真栈，2026-09-04）

> AA-26 证据契约：manifest + DB 快照 + 日志 + SHA-256。本目录是 G0-10（AM1 单链路 E2E）的完整证据。
> 测试过程与断言见 `docs/告警-测试记录.md` §3；缺陷见 `docs/告警-BUGLOG.md`（BA-14~21）。

## 1. 目录清单

```
e2e/
├── README.md                          ← 本文件
├── screenshots/                       ← 全流程浏览器截图（用户验收要求：告警+全流程）
│   ├── 01-prometheus-alerts-firing.png            Prometheus /alerts：Sloth 烧损率 firing（page/ticket 双档）
│   ├── 02-alertmanager-group-ticket-only.png      Alertmanager：ticket 档分组
│   ├── 03-alertmanager-page-ticket-groups.png     Alertmanager：page+ticket 双分组并存
│   ├── 04-prometheus-burn-rate-zero-after-resolve.png   resolved 后烧损率归零
│   └── 05-prometheus-burn-rate-6h-spikes-and-recovery.png  6h 窗口尖峰与恢复曲线
└── 195-raw/                           ← 195 终态快照五件套（scp 原样拉回）
    ├── db-final-snapshot.txt          六表终态全 dump（inbox/event/incident/run/task/attempt/账本/报告/slot）
    ├── control-app-key-logs.txt       启动自检+webhook 202+Worker 关键日志摘取
    ├── alertmanager-state.txt         AM API 终态（groups/receivers）
    ├── prometheus-final-state.txt     Prometheus /api/v1/alerts+queries 终态
    └── manifest.sha256                195 侧生成时点的 SHA-256 清单
```

## 2. 完整性

`195-raw/manifest.sha256`（195 生成）与本地拉回副本逐字节核对**一致**：

| 文件 | SHA-256（前 16 位） |
|---|---|
| alertmanager-state.txt | `10ea61f6d5d0113f...` |
| control-app-key-logs.txt | `b8d043d67aea638b...` |
| db-final-snapshot.txt | `217aa17cd9d4ca75...` |
| prometheus-final-state.txt | `22d69bb65366c5cf...` |

## 3. 时间线（关键节点，195 时钟）

| 时刻 | 事件 |
|---|---|
| E2E 前段 | flagd `paymentFailure` 注入 → Prometheus Sloth firing（page/ticket）→ AM 分组投递 → webhook 202 → incident/rca_run/rca_task 铸造 |
| 修复窗口 | BA-14（response_format 被忽略→REJECTED_MALFORMED）与 BA-15（kubectl 诱导+prometheus 工具集静默禁用）定位→修复→重部署；修复前留下 1 FAILED run / 1 DEAD task（终态快照中如实保留，不清洗） |
| 14:36:04 | SIGKILL 演练：kill control-app（此刻 task LEASED/账本 STARTED；容器未自拉起记 BA-17，手动 start） |
| 14:46:02 | 租约到期回收 RETRY_WAIT；悬挂账本超宽限 → UNKNOWN |
| ~14:49 | 重领 attempt 2 → SUCCEEDED，报告唯一 |
| 15:42:32.997 | 真实 AM resolved 通知投递（停注入后 1h 窗口自然稀释，BA-20）→ inbox PROCESSED → incident RESOLVED 15:42:33.8 |
| 15:50:28 | 合成 resolved 清场演练告警（独立 incident）归并 |
| 终态 | 六表快照：2 incident 全 RESOLVED、7 run、7 task、账本 10（9 SUCCEEDED+1 UNKNOWN）、报告 6 全 STRUCTURE_VALIDATED 无重复 |

## 4. 证据纪律说明

- 快照与日志经敏感串扫描：无 API key / bearer 字面量（密钥永不入文档/代码/日志）。
- **过程日志缺失声明**：E2E 前段的 phase 日志（e2e-phase1*.log、report json 等）因 BA-16 同步误删事故丢失，未入本包；终态快照完整覆盖断言所需全部事实，损失范围如实记录于 BA-16。
- 截图为浏览器经 SSH 隧道访问 195 内网页面所得，未暴露公网端口（195 公网零暴露纪律）。
