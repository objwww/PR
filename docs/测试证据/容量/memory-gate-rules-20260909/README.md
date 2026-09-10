# BA-54 三档内存闸 Prom rules 落码证据（2026-09-09，195）

## 结论
- `memory-gate.yml`（group=memory-gate，3 条规则）已落 `/opt/build/pr/deploy/alert/prometheus/rules/`，
  经 `docker kill --signal=HUP prometheus-am0` 热加载后，`/api/v1/rules` 实证三条规则在场且 **health=ok、lastError 为空**：
  - MemoryGateTier1：`< 1.2*1024*1024*1024`，for 5m，tier=t1 / severity=info
  - MemoryGateTier2：`< 768*1024*1024`，for 2m，tier=t2 / severity=warning
  - MemoryGateTier3：`< 512*1024*1024 or on(instance) (ALERTS{alertname="MemoryGateTier3",alertstate="firing"}==1 and on(instance) MemAvailable < 1536*1024*1024)`，无 for（立即），tier=t3 / severity=critical
  - 当前 state=inactive（真值 3744256000B ≈ 3.49GiB，远高于全部阈值，符合预期）
- rule_files glob 覆盖结论：prometheus.yml `rule_files: [/etc/prometheus/rules/*.yml]`，rules 目录为**目录级 bind mount**（`/opt/build/pr/deploy/alert/prometheus/rules → /etc/prometheus/rules`，ro），新文件宿主落位后容器内即刻可见（md5 对拍实证），**无需改 prometheus.yml**。
- 备份 sha256：`4c5e3143040da95ce406d1aedd4fc287c8aa1ed0e64d5c2e8b8c0fef5df0dd42`（/root/prom-rules-backup-20260909.tgz，含变更前 rules/ 三文件全量）。
- md5 对拍（BA-62 权威方法 `docker exec cat | md5sum`）：宿主 `3af53b4fc5a840c9cdd7fd2696407909` == 容器内视图一致；本地仓库镜像同值。

## 备料文档写法修正（dry-run 实证）
- **`1.2Gi / 768Mi / 512Mi` 后缀字面量在 Prometheus v3.13.1 不可用**：API 返回
  `parse error: bad number or duration syntax`（见 dryrun-json/02、03、04、08）。全部阈值改乘法形式
  （`512*1024*1024` 等），三条最终表达式 API 解析全部 success（dryrun-json/11、12、13）。
- 正向探针：`< 100*1024*1024*1024` 返回真值序列（dryrun-json/14），证明比较语义正常、空结果纯因水位高于阈值。
- 序列标签恰为 `{job="node-exporter-host1", instance="10.250.250.1:9100"}`（dryrun-json/01/15）。

## t3 滞回取舍
- 采用 **ALERTS 自引用惯用法，但必须带 `on(instance)` 显式匹配**：ALERTS 序列额外携带
  alertname/alertstate/tier/severity 标签，朴素自引用（无 on()）两侧标签集不相等、`and` 永不相交，滞回必然失效
  ——这是该惯法"不可靠"的真实来源。表达式语法已经 API 验证（dryrun-json/13）。
- 已知约束（写进 rules 文件注释）：①恢复侧"≥1536MiB 持续 10min"严格窗无法用纯 ALERTS 惯用法表达，
  本实现为回到 1536MiB 以上即恢复，严格 10min 窗需 recording rule 辅助，本次取舍未做；
  ②Prometheus 重启后 ALERTS 由规则重放重建，滞回状态丢失；
  ③滞回窗口内 annotation 的 `$value` 显示告警状态位 1（属惯用法已知表现，annotation 已注明以查询真值为准）。

## 校验与加载流程（BA-61/BA-62 教训执行面）
1. `docker cp` rules 文件进容器 `/tmp/memory-gate.yml` → `docker exec prometheus-am0 promtool check rules` →
   `SUCCESS: 3 rules found`，exit=0（p2_promtool.txt）；**未对挂载路径直接 check**。
2. md5 对拍（宿主 vs `docker exec cat | md5sum`）一致后才 HUP。
3. `docker kill --signal=HUP prometheus-am0`（唯一容器动作，无 restart/新建）→ 12s 后
   `curl /api/v1/rules` 全量落盘（p2_api_rules.json），memory-gate 组摘录见 rules-after-hup-excerpt.json。

## 文件清单
| 文件 | 说明 |
|---|---|
| memory-gate.yml | rules 文件副本（=远程 3af53b4f…，=本地仓库镜像） |
| rules-after-hup-excerpt.json | HUP 后 /api/v1/rules 的 memory-gate 组摘录（3 规则+health） |
| p2_api_rules.json | HUP 后 /api/v1/rules 全量 dump（14349B） |
| p2_promtool.txt | 容器内 promtool check rules 输出 |
| p2_verify.txt | 落位/promtool/md5 对拍/HUP/在场实证全流程记录 |
| p2_recon.txt | 前置只读勘察（容器名/mounts/prometheus.yml/rules 目录/版本） |
| p2_dryrun.txt | dry-run 第一批：真值 + Gi/Mi 后缀字面量证伪 + 乘法式 |
| p2_dryrun2.txt | dry-run 第二批：三条最终表达式 + 正向探针 |
| dryrun-json/ | 15 个查询原始 JSON 留证 |

## 边界声明
- 未改 prometheus.yml、未动 alertmanager.yml；仅新增 rules 文件一个。
- 变更前已备份（tar + sha256 见上）；未 restart/删除任何容器；热加载仅 HUP prometheus-am0。
- 动作面（RESOURCE_MODE_CHANGED 事件、领取暂停联动、readiness fail 表达面）仍缺，BA-54 不关单。
