# V31 生产库应用证据（195 部署面，2026-09-08 17:24-17:26 UTC）

## ① 先备份后变更

pg_dump 全库（deploy-postgres-1 容器内执行）：
/opt/backups/pr_agent-pre-v31-ba52-20260908T172421Z.sql.gz（132M）

## ② V31 双树 sha256 对拍（BA-34⑧ 同律）

```
1ea37f151c81f13f265073a71bd3d52e3ce3301a231e5fb4cb0a47e4b4c0447c  /opt/projects/pr_agent_it/control-app/src/main/resources/db/migration/V31__ba52_canary_decision_fk_deferred.sql
1ea37f151c81f13f265073a71bd3d52e3ce3301a231e5fb4cb0a47e4b4c0447c  /opt/build/pr/control-app/src/main/resources/db/migration/V31__ba52_canary_decision_fk_deferred.sql
```

## ③ flyway one-shot（deploy migrate 服务直挂源树 migration 目录；主控台逐字转录）

```
Flyway OSS Edition 10.22.0 by Redgate
Database: jdbc:postgresql://postgres:5432/pr_agent (PostgreSQL 16.15)
Successfully validated 31 migrations (execution time 00:00.087s)
Current version of schema "public": 30
Migrating schema "public" to version "31 - ba52 canary decision fk deferred"
Successfully applied 1 migration to schema "public", now at version v31 (execution time 00:00.013s)
```

## ④ 落库形态验证

```
flyway_schema_history 尾 2 行：
31|ba52 canary decision fk deferred|t
30|am6 canary window verdict|t

pg_constraint：
canary_route_decision_run_id_fkey | deferrable=t | deferred=t
```

## ⑤ 应用面

control-app 无需重启（SPRING_FLYWAY_ENABLED=false，迁移唯一执行者 = migrate one-shot；
约束时点后移对运行中语句即时生效）；health = 200。

## ⑥ 两键翻转（NATIVE 能力就绪，E2E 双态前置操作员步骤）

- 备份：/opt/build/pr/deploy/.env.bak-pre-m6-native-20260908T173454Z（600）
- 新增两键（compose 已有 :? 空默认映射）：
  APP_ALERT_NATIVE_METRICS_EXPR=oa_duplicate_orders_current{job="order-arena"}
  APP_ALERT_NATIVE_TOOL_REGISTRY_DIGEST=am6-native-m6-01:metrics,logs,change
- docker compose up -d control-app 重建 → health=200
- 双态证据：E2E-AM6-00 false 轮（nativeReady=false，缺件清单含 metricsExpr/toolRegistryDigest、
  不出具 capabilityDigest）与 true 轮（nativeReady=true + 64hex capabilityDigest）。
