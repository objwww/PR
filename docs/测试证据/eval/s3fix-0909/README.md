# S3 smoke 复跑证据（s3fix-0909）——BA-64 修复后全链验证

- 时间：2026-09-09 15:18~15:39 UTC（23:18~23:39 CST）
- 启动器：`sh /tmp/cc-evalrun-glm5.sh deploy/alert/eval/eval-scenarios-s3.yml s3fix-0909`（195）
- eval_run：`e512a37c-656b-4d91-9daf-f67db6393223`，registry=S3 单场景 ×2 轮（F1 幂等失效黄金链）

## 批指标（driver 末行，01-eval-run.log）

`coverage=0.5 conditional=0.0 e2e=0.0 unresolvedRate=0.0 tp=0 fp=0 fn=2`
usage ledger：UNMATCHED / no_rows_under_run_key（符合 NATIVE 零模型调用口径）。

## 逐 Case（02-db-after.txt）

| Case | verdict | 说明 |
|---|---|---|
| S3R1 | **DECIDABLE** | 全链真实走通：chaos on(F1)→ArenaDuplicateOrders firing（15:18:58）→alertmanager webhook→control-app incident 5552d241 事件（generation 66，last_event_at 15:19:30）→rca_run ace9d2ba SUCCEEDED→报告 fb77d270→评分。fn=1 因 actualRootCause=NO_CONFIRMED_ROOT_CAUSE——Native 确定性执行器如实报 UNKNOWN，不伪造根因，符合 Native 基线口径 |
| S3R2 | TIMEOUT_OR_ABSENT | run_not_found：同一 alert group 在 R1 后仍处于活动期，alertmanager `repeat_interval=4h` 抑制同组重复通知 → R2 无新 webhook→无新 RCA run。**测试编排与 alertmanager 重复通知节奏冲突，非产品断链** |

## 恢复侧验证

- chaos off → `oa_duplicate_orders_current` 归零（Gauge 真值）
- firing 列表清空（Prometheus ALERTS 与 alertmanager /api/v2/alerts 双 0）
- resolved webhook 送达：incident 5552d241 status=FIRING→**RESOLVED**（resolved_at=15:39:30，send_resolved 链路同步验证）
- 现场无残留（flagd paymentFailure=off 已于修复前核认）

## 结论

1. BA-64 修复有效：通知链双向（firing + resolved）实证恢复，BA-64 关闭。
2. S3R1 是 Native 口径下第一条"真实现场证据→incident→RCA→报告→评分"的完整链路证据。
3. 遗留评测设计问题（另立项）：多轮场景受 alertmanager repeat_interval=4h 抑制，R2 恒 run_not_found——全量批 5 场景×2 轮结构需在评测方案中解决（等 resolved+repeat 窗、或 eval 专用 AM 配置、或轮次改单轮多场景）。
