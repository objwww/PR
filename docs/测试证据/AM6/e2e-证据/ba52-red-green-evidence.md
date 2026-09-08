# BA-52 RED 证据（TDD 红——修复前真栈复现）

- 时间：2026-09-08 17:1x UTC（195 部署段 IT 树 /opt/projects/pr_agent_it）
- 命令：mvn -s maven-settings-aliyun.xml -pl control-app -am verify -Dtest=NoopMatchAll -Dit.test=PostgresCanaryRoutingIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
- 前置：仓库树尚无 V31 迁移（schema 停在 V30，FK 为 NOT DEFERRABLE）
- 主控台捕获（逐字转录自执行输出；不涉任何密钥）：

```
[ERROR] Tests run: 5, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 3.797 s <<< FAILURE! -- in com.objwww.pr.control.it.PostgresCanaryRoutingIT
[ERROR] com.objwww.pr.control.it.PostgresCanaryRoutingIT.decisionAppendBeforeRunInsertCommitsAsOneTransaction -- Time elapsed: 0.093 s <<< ERROR!
org.springframework.dao.DataIntegrityViolationException:
]; ERROR: insert or update on table "canary_route_decision" violates foreign key constraint "canary_route_decision_run_id_fkey"
Caused by: org.postgresql.util.PSQLException: ERROR: insert or update on table "canary_route_decision" violates foreign key constraint "canary_route_decision_run_id_fkey"
[ERROR]   PostgresCanaryRoutingIT.decisionAppendBeforeRunInsertCommitsAsOneTransaction:231->lambda$decisionAppendBeforeRunInsertCommitsAsOneTransaction$5:233 » DataIntegrityViolation PreparedStatementCallback; SQL [INSERT INTO canary_route_decision (
]; ERROR: insert or update on table "canary_route_decision" violates foreign key constraint "canary_route_decision_run_id_fkey"
[ERROR] Tests run: 5, Failures: 0, Errors: 1, Skipped: 0
[INFO] control-app ........................................ FAILURE [ 18.118 s]
[INFO] BUILD FAILURE
```

- 判读：5 测试中仅新增 BA-52 复现测试（decisionAppendBeforeRunInsertCommitsAsOneTransaction，
  生产铸造顺序镜像：同事务先 route() append 决策行、后 insertRouted() 落 run 行）红；
  既有 4 测试绿——缺陷被隔离到"决策行先落/run 行后落"的顺序面。

# 生产侧同因故障现场（195 真栈，2026-09-08 17:08 UTC，postgres 容器日志逐字转录）

```
2026-09-08 17:08:04.423 UTC [29194] ERROR:  insert or update on table "canary_route_decision" violates foreign key constraint "canary_route_decision_run_id_fkey"
2026-09-08 17:08:04.423 UTC [29194] DETAIL:  Key (run_id)=(5a93bf43-6445-462f-8f78-640306f714a9) is not present in table "rca_run".
2026-09-08 17:08:04.423 UTC [29194] STATEMENT:  INSERT INTO canary_route_decision (
（同型 ERROR 共 5 次 = inbox 重试 5 次后 DEAD_LETTER；alert_inbox.last_error = "attempt-exhausted: db-error: DataIntegrityViolationException"）
```

# BA-52 GREEN 证据（TDD 绿——V31 落地后同一线束）

- 时间：2026-09-08 17:2x UTC；V31__ba52_canary_decision_fk_deferred.sql 同步至 195 IT 树后同命令重跑：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.668 s -- in com.objwww.pr.control.it.PostgresCanaryRoutingIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 全量回归门（同树 mvn clean verify，日志 = 本目录上级 logs/m6-ba52-verify.log）：
  control-app surefire 1087 / failsafe 121（120+1）/ 0F / 0E / 全局 0 skipped，BUILD SUCCESS。
