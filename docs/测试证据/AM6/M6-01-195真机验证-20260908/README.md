# M6-01 195 真机验证证据包（2026-09-08）

- **验证对象**：M6-01 1% Native Canary 执行面接线，同步 HEAD = `6ae5587`（fb4fe20 落码 + a08d7be compose 两键映射 + 9fa6c9e Gatus CRLF 门 + 6ae5587 IT 夹具对齐 C-70）。
- **同步配方**：`git -c core.autocrlf=false archive --format=tar.gz HEAD -- pom.xml shared-kernel control-app deploy .env.example order-arena arena-chaos-admin notify-app` → scp → sha256 双侧一致（BA-34⑧）→ 解包 `/opt/build/pr`（部署树）与 `/opt/projects/pr_agent_it`（IT 树）→ 抽查文件 `git cat-file` blob 对拍。
- **变更前备份**：195 `/opt/build/pr/backups/pre-m6-a08d7be-{deploy,it}-tree.tar.gz`。

## 测试段（真 PG 全量，零跳过）

- 红证据 `m6-verify-9fa6c9e.log`：failsafe 115 run，2F/4E——BA-51（git archive CRLF 传输击穿 Gatus 契约门）+ BA-52（IT 夹具落后 C-70 类型化领取面，含 it06_6 双侧同卡 QUEUED 的空洞通过旁证）。
- 绿证据 `m6-verify-6ae5587.log`：`mvn clean verify -pl control-app -am` → surefire 1087 + failsafe 115，0F / 0E / **0 skipped**，零 APPLICATION FAILED。
- 对照：本地基线 965 run / 21 skipped（`*IT` 本机无 Docker 全跳过，不计完成证据）。

## 部署段

- package：`m6-package-6ae5587.log`（`JAVA_HOME=/opt/jdk-21.0.12.1+1`，exec.jar 40,188,026 B）。
- 迁移：flyway one-shot `docker compose up migrate`，V29→V30 "am6 canary window verdict" apply 成功，exit 0。
- 验表：`canary_evidence_sample` / `canary_route_decision` / `canary_window_verdict` 三表在位；`control_app` 角色授权**仅 INSERT/SELECT**（append-only，无 DELETE/UPDATE/TRUNCATE）；`canary_window_verdict` 列形态与 V30 DDL 一致（rollout_id+window_seq 双窗键、candidate/capability/rollout_policy 三 digest、critical_pass、from/to_percent、raw/strata/absolute_slo/control/scored 五 JSON 面、evidence_refs、eligible_incidents）。
- 上线：`docker compose build control-app`（pr-agent/control-app:0.0.1-SNAPSHOT，sha256:fd35db9d…）+ `up -d` → migrate 依赖链复验 → `/actuator/health` **200 {"status":"UP"}**，`Started ControlApplication in 10.835 seconds`，日志零 APPLICATION FAILED。

## 冒烟段（GET /api/canary/status，真容器）

- `m6-smoke-posture.log`：无 bearer → **401** `{"error":"unauthorized"}`（C-75 常量时间比较、401 零仓储触达）；release bearer → **200** `{"nativeReady":false,"missing":["metricsExpr","toolRegistryDigest"],"nativeDecisions":0}`——两键未设 = 缺件 fail-closed 姿态精确（capabilityDigest 条件键正确缺席）。
- `m6-smoke-flip-revert.log`：备份 .env（`.env.bak-pre-m6flip-20260908234240`）后落 `APP_ALERT_NATIVE_METRICS_EXPR` / `APP_ALERT_NATIVE_TOOL_REGISTRY_DIGEST` 两键重建 → **200** `{"nativeReady":true,"capabilityDigest":"b251c71c62cf6dcee0da9d97290fcf338819f0ab6d0fc894cef70300917fb2fc","nativeDecisions":0}`（探针就绪、指纹 64hex、NATIVE 零准入=canary percent 未设）；字节级恢复备份 → 回 `nativeReady:false` 默认 fail-closed。
- **结论**：M6-01 运行时姿态双态（缺件/就绪）真容器实证通过；1% 放量与 195 .env 真值落键仍属 live 前置清单（与 O-67 预算设值同批），验证后不留翻转态。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| m6-verify-9fa6c9e.log | 195 mvn verify 红证据全量日志（2F/4E） |
| m6-verify-6ae5587.log | 195 mvn verify 绿证据全量日志（0F/0E/0 skip） |
| m6-package-6ae5587.log | 195 maven package 日志 |
| m6-smoke-posture.log | 冒烟：401 + 缺件姿态（195 原件 /opt/projects/pr_agent_it/） |
| m6-smoke-flip-revert.log | 冒烟：两键翻转 → nativeReady 翻转 → 字节级回滚（同上） |
| m6-smoke.sh / m6-flip-revert.sh | 冒烟脚本（stdin 经 `tr -d '\r'` 注入远端 bash；密钥只经 shell 变量，绝不回显/落档） |
