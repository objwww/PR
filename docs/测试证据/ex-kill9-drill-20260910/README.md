# EX 线 kill -9 崩溃恢复演练证据（195 真机，2026-09-10）

目的：补 C 门审计跟进项 1——EX 线（A2 租约/心跳/取消 + A3 四阶段恢复）崩溃恢复此前只有
崩溃点注入法（IT 内 DB UPDATE 伪造崩溃窗）覆盖，本演练用**真杀进程**（SIGKILL → JVM 无
finally/钩子机会）取证。

## 演练设计

- 触发方式：**webhook 直打**（POST /webhooks/alertmanager，bearer 取自 195
  /opt/build/pr/deploy/.env，不回显）。理由：走真实 intake→incident→run 铸造链路，
  快且现场干净；eval smoke 更重且引入 harness 噪音。canary percent=100 → run 全路由
  NATIVE，正好压在 EX-A3 驱动面上。
- 命中策略：单 run 执行仅 ~0.05–0.36s，盲杀命中 RUNNING 窗口概率太低。改为
  **10 连发 firing**（Kill9DrillProbe01..10，外加校准弹 Probe00），串行执行形成
  连续繁忙窗；`docker logs -f` 盯首条 `native 全链完成` 日志为扳机，+120ms 后
  `docker kill -s KILL deploy-control-app-1`。
- 恢复机制期望（代码校准，RcaWorker.recoverExpired）：task 租约 PT10M（默认，195 无
  覆盖）到期 → 恢复扫描回收 LEASED→RETRY_WAIT（退避 PT1M）→ 重领重驱；悬挂账本
  STARTED/PENDING 超 hangingGrace PT10M → UNKNOWN。

## 关键时间线（UTC，秒级/毫秒级）

| 时刻 | 事件 |
|---|---|
| 10:28:51.861 | 校准弹 Probe00 投递，202 |
| 10:28:54.299→54.664 | Probe00 run 90cc1185 RUNNING→SUCCEEDED（链路校准 OK） |
| 10:30:08.198–08.244 | 10 连发全部 202 |
| 10:30:08.877 | 扳机：首个 `native 全链完成`（Probe01 run d70cf81f） |
| 10:30:09.093 | **容器死亡**（docker inspect finishedAt，exitCode=137） |
| 10:30:09.018 | 被杀 run 05b2d85e 的 task 租约 lease_until（claim+10min） |
| 10:30:09.016 | 被杀 run 最后一笔 settled 工具调用（prometheus.query SUCCESS+result_ref） |
| 10:31:42.930 | 人工 `docker start`（见偏差 D1） |
| 10:31:43.172 | 容器新 PID 32263 起来 |
| 10:31:55.976 | RcaWorker 启动（JVM 启动 12.9s） |
| 10:31:56+ | 剩余 7 个 QUEUED run 被新 worker 逐个正常完成 |

## 偏差记录

- **D1（红直报）**：`docker kill -s KILL` 后 restart=unless-stopped **未自动拉起**。
  dockerd 日志：`ShouldRestart failed ... error="restart canceled"
  hasBeenManuallyStopped=true exitStatus={137 2026-09-10 10:30:09.093}`。
  docker CLI 的 kill/stop 被 daemon 标记 manual-stop，restart policy 不再生效——
  真崩溃恢复依赖"容器自动拉起"的断言在此路径上不成立；若用
  `docker exec deploy-control-app-1 kill -9 1`（容器内杀 PID1）则不在 manual-stop
  语义内，预期会自动重启。本演练在 kill 后 2 分 34 秒人工 start，恢复语义验证不受影响
  （租约回收按 lease_until 到期驱动，与重启时刻解耦），但"自动拉起"一节如实记 FAIL/
  偏差。

## 证据文件

- `01-baseline.txt`：演练前 DB 行数基线 + 近期 NATIVE run 时长 + 内存磁盘
- `k9-drill-fire.sh` / `02-fire-and-kill.txt`：投递+狙击+kill 全量输出原文
- `03-post-kill-snapshot.txt`：kill 后 DB 冻结态（run/task/attempt/账本四表对照）
- `04-restart-and-crashed-run-detail.txt`：未自动重启证据 + 被杀 run 的 DAG 子任务态
- `05-manual-start.txt`：人工拉起与 worker 重启日志
- `k9-watch.sh` / `06-watch-recovery.txt`：恢复窗口轮询（20s/拍）+ 恢复日志行
- `07-final-assertions.txt` / `08-final-assertions-fix.txt`：终态五断言取证查询
- `09-final-site-state.txt`：现场终态（fleet 健康/无 ERROR/AM 无残留/内存磁盘）

## 恢复时间线（实测，UTC）

| 时刻 | 事件 |
|---|---|
| 10:30:09.093 | SIGKILL 落地，容器 exit 137 |
| 10:31:43.172 | 人工 docker start（D1 偏差） |
| 10:31:55.976 | RcaWorker 启动，恢复扫描每轮执行 |
| 10:31:57–58 | 7 个未受波及 QUEUED run 全部正常 SUCCEEDED |
| 10:40:09.122 | **租约到期回收**：task aa4407ef LEASED→RETRY_WAIT（lease_until 10:40:09.018 + 104ms 扫描延迟） |
| 10:40:09.129 | 悬挂调查记录 725ac4d7 STARTED→UNKNOWN（崩溃回收） |
| 10:41:09.251–309 | 重驱 attempt#2 执行完成，run SUCCEEDED（退避 PT1M 整） |

## 断言结论

1. **无幽灵 run — PASS**：11 incident/11 event/11 run/44 task/12 attempt/33 账本行，
   增量与期望逐一相等；ext/model 账本行数零增量；恢复全程 0 条 ERROR/23503。
2. **checkpoint 面完整 — PASS**：被杀 run 账本 seq1 prometheus.query SUCCESS+result_ref
   （证据行存在，FK 完好）；重驱仅新增 seq2/3（call_seq 跨 attempt 单调），
   result_ref 状态机无非法迁移。
3. **UNKNOWN 语义 — PASS**：悬挂调查记录 STARTED→UNKNOWN 诚实归档（不猜结局）；
   metrics 阶段③零触网幂等收尾（prometheus_calls=1，不重复计费不当作未调用）；
   预算 seq1 COMMITTED、seq2/3 FAILED 释放 RELEASED，无 PROVISIONAL 悬挂。
4. **事件流连续 — PASS（空真）**：last_event_seq 1→1 不变，rca_event 该 run 0 行
   （native 路径本就不写事件流），无洞无重。
5. **终态收敛 — PASS**：run SUCCEEDED，task DONE epoch 1→2 attempt 1→2；
   attempt#1 STARTED 孤儿行原样保留（ABANDONED 枚举存在但主代码无写入点，
   孤儿行即崩溃证据，判定为设计内）；change/logs 子任务 DEAD 与对照组
   （未崩溃的 Probe04 等）形态完全一致（INVALID_INPUT 系环境既有工具入参问题，
   与崩溃恢复无关）。

## 现场处置

- 11 个演练 incident/run 保留，alertname=Kill9DrillProbe*、fault_type=DRILL 自标识来源；
  incident 状态滞留 FIRING（演练未投递 resolved，属预期）。
- 未触碰 alertmanager（直打 control-app webhook），AM 侧 0 残留；flagd 零改动；
  除 deploy-control-app-1（kill+start）外无任何容器被动过。
