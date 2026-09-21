# BA-193 flagd 活性告警备料说明（2026-09-19 立案，仓库内备料，部署由主会话统一做）

立案背景：195 真机 flagd 容器（opentelemetry-demo 栈）内存顶到限额 88%
（65.9MiB/75MiB）后僵死——进程在、gRPC 不服务→payment 扣款卡在旗标求值→
checkout 100% 失败，烧损告警烧 4 小时无人知；docker restart flagd 后立即恢复
（8/8 单 2 秒全过）。Prometheus（prometheus-am0）实测完全没有 flagd 抓取面：
`up` 无 flagd 行、`{job=~".*flagd.*"}` 零序列。

交付物：
- `deploy/alert/prometheus/rules/flagd-liveness.yml`：FlagdDown（page）/
  FlagdMemoryNearLimit（warning）/FlagdEvaluationStall（ticket）三规则。
- `deploy/alert/prometheus/flagd-liveness-test.yml`：promtool 五用例（3 触发 +
  1 短闪断不报 + 1 健康反向），与 BA-189 测试文件同构，放在 rules/ 目录外
  （rules/*.yml 会被 prometheus 装载，测试文件绝不可入该目录）。

## 一、抓取面建议（prometheus.yml scrape_configs 增补，部署窗执行）

```yaml
  # BA-193 flagd 活性补采：job 落地后 up{job="flagd"} 即供 FlagdDown/Stall 规则，
  # process_resident_memory_bytes 供 FlagdMemoryNearLimit（无需 cAdvisor）。
  # 端口 8014 已经 2026-09-20 195 健康窗实测：首字节 0.09s，全量序列含
  # feature_flag_flagd_impression_total / process_resident_memory_bytes / go_memstats_*。
  - job_name: flagd
    static_configs:
      - targets: ['flagd:8014']
```

内存规则已定稿用 flagd 自身 /metrics 的 `process_resident_memory_bytes`（RSS，
天然带 job/instance 标签）——195 实测无 cadvisor 容器、宿主 10.250.250.1:8080
与 127.0.0.1:8080 均无 cAdvisor 序列，cAdvisor 两方案均废弃，不引入新容器。

## 二、降级方案（本节已随 2026-09-20 实测作废，仅存档）

实测证明 flagd:8014/metrics 可用且含 process_resident_memory_bytes 与求值计数器，
cAdvisor 降级、容器存活位降级、docker healthcheck 兜底三条路径均不再需要。
若未来 metrics 口被 flagd 版本升级移除，再回本节重启降级设计。

## 三、195 实测确认项（2026-09-20 健康窗已实测 1~4，仅余 promtool 实跑）

1. ~~flagd metrics 真实端口~~ ✅ **flagd:8014/metrics 可用**：健康时首字节 0.09s；
   同日前兆观测——RSS 顶到 96%（71.98MiB/75MiB）时首字节劣化到 21s，
   预防性 `docker restart flagd` 后回落 57% 并恢复 0.09s。8016 口无 metrics。
2. ~~求值指标真实名~~ ✅ **feature_flag_flagd_impression_total**（按
   flag key/reason/variant 打标签，OTel instrumentation 形态），另有同义的
   feature_flag_flagd_result_reason_total；官方旧名 flagd_evalution_reason_total
   在本版本 /metrics 全量中**不存在**。规则、头注释、测试文件已同步改定。
3. ~~cAdvisor 容器选择器~~ ✅ **作废**——195 无 cadvisor 容器，宿主
   10.250.250.1:8080 连接失败、127.0.0.1:8080 为 control-app（401），均非
   cAdvisor。内存规则改用 flagd 自身 `process_resident_memory_bytes`。
4. ~~容器内存限额复核~~ ✅ `docker inspect flagd` HostConfig.Memory=**78643200**
   （75MiB），规则阈值 0.85*75*1024*1024 与之相符。
5. ~~promtool 实跑~~ ✅ 2026-09-20 prometheus-am0 容器内实跑
   `promtool test rules flagd-liveness-test.yml` → **SUCCESS**（五用例全绿，
   3 触发 + 1 短闪断不报 + 1 健康反向）。规则可进装载窗。

## 四、白名单条目清单（r7 bundle 补登，照 BA-189 形状）

canary percent=0 时非白名单 stickiness key 恒路由 BUCKETED_HOLMES 永不铸 run
（BA-180 定谳），三个新症状必须补登。条目文本（追加在 BA-189 现役 50 条之后，
双形态=裸 alertname + `|job=flagd`，与 MemoryGate/BA-189 登记纪律同律）：

```
alertname=FlagdDown
alertname=FlagdDown|job=flagd
alertname=FlagdMemoryNearLimit
alertname=FlagdMemoryNearLimit|job=flagd
alertname=FlagdEvaluationStall
alertname=FlagdEvaluationStall|job=flagd
```

诚实标注：FlagdMemoryNearLimit 输出序列来自 flagd 自身 /metrics 的
process_resident_memory_bytes（带 job=flagd 标签），双形态均可真实匹配；
FlagdEvaluationStall 经 sum 聚合后无 job 标签，`|job=flagd` 形态按当前规则
不会真实匹配，保留仅为与 BA-189 登记纪律一致并防御规则将来加 job 标签的情形。
bundle 发布脚本照 `publish-ba189-infra-liveness-whitelist.sh` 改 CONTENT 文件名
与核对断言语句即可，由部署窗执行。

## 五、装载收官记录（2026-09-20 17:2x，主会话部署窗）

- prometheus.yml job flagd 已装载（仓库源同步 195）；三规则 ok inactive（健康不警）；
  输入序列实测 up=1、process_resident_memory_bytes=44MB、求值速率 0.114/s。
- 白名单 bundle digest=064bd2acc30929828f3635a9fad0aaf5949061b26ff989f187c45c5065128d44
  激活（56 条），policy_version=ba193-flagd-liveness-whitelist-20260920。
- **教训**：单文件 bind mount 陈旧 inode——宿主整体替换过 prometheus.yml 后容器持旧
  inode，SIGHUP reload 读旧文件且无任何报错（规则文件因目录挂载反而生效，造成
  "半生效"假象）。配置变更后以 /api/v1/status/config 生效面核验，必要时 restart。
- **观察项**：prometheus TSDB WAL segment 171 损坏，compaction 每 2h 报
  "unexpected full record"——不影响新数据写入与查询，后续窗口评估清理。
