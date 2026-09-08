-- M6-02 观察面成账：预算/延迟/错误率/人工分歧 四维台账（只读查询脚本）
--
-- 形态（落码方案 M6-02 节）：验收台账 SQL 固化为 deploy/policy/ 下只读查询脚本。
-- 全部语句为单条 SELECT（零 DDL/DML），在生产库以只读角色执行：
--   docker exec deploy-postgres-1 psql -U control_app -d <db> -f - < 本文件
--   （或逐段执行；psql 默认 autocommit，SELECT 无写面）
-- 口径纪律（E-20 / 技术方案 §7）：同窗对照 = 双侧同时间段（rca_run.created_at 同窗），
-- 禁 before/after 对比；绝对值与相对值并列呈现，不预聚合结论。

\echo ''
\echo '==== 维度一：预算（token 成本，按 engine 分桶；usage_missing 诚实单列） ===='
SELECT r.engine,
       date_trunc('day', rep.created_at)                    AS day,
       count(*)                                             AS reports,
       sum(rep.total_tokens)                                AS total_tokens,
       sum(CASE WHEN rep.usage_missing THEN 1 ELSE 0 END)   AS usage_missing_cnt
  FROM rca_report rep
  JOIN rca_run r ON r.id = rep.run_id
 GROUP BY r.engine, date_trunc('day', rep.created_at)
 ORDER BY day DESC, r.engine;

\echo ''
\echo '==== 维度二：延迟（run 起止时长 ms，按 engine 分位数；空区间=样本不足） ===='
SELECT engine,
       count(*)                                                     AS runs,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - started_at)) * 1000) AS p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - started_at)) * 1000) AS p95_ms,
       max(extract(epoch FROM (finished_at - started_at)) * 1000)   AS max_ms
  FROM rca_run
 WHERE started_at IS NOT NULL AND finished_at IS NOT NULL
 GROUP BY engine
 ORDER BY engine;

\echo ''
\echo '==== 维度三：错误率（run 终态 + driver 任务 DEAD 面，按 engine 分桶） ===='
SELECT engine,
       count(*) FILTER (WHERE state = 'SUCCEEDED')          AS succeeded,
       count(*) FILTER (WHERE state = 'FAILED')             AS failed,
       count(*) FILTER (WHERE state IN ('QUEUED','RUNNING','REPORTING')) AS in_flight,
       round(count(*) FILTER (WHERE state = 'FAILED')::numeric
             / NULLIF(count(*) FILTER (WHERE state IN ('SUCCEEDED','FAILED')), 0), 4) AS fail_rate
  FROM rca_run
 GROUP BY engine
 ORDER BY engine;

\echo ''
\echo '==== 维度三附：对照结论落账进度（engine_comparison 行数与差异率） ===='
SELECT count(*)                                         AS comparisons,
       count(*) FILTER (WHERE disagree_flags <> '[]'::jsonb) AS flagged,
       count(*) FILTER (WHERE noise_baseline IS NOT NULL)    AS with_noise_baseline,
       min(created_at)                                   AS first_recorded,
       max(created_at)                                   AS last_recorded
  FROM engine_comparison;

\echo ''
\echo '==== 维度四：人工分歧面（六维差异标记明细——无 GT 只记 disagreement 不判对错） ===='
SELECT ec.native_run_id,
       ec.shadow_exec_ref,
       ec.snapshot_digest,
       flag->>'dim'                                       AS dim,
       flag->'holmes'                                     AS holmes_side,
       flag->'native'                                     AS native_side,
       ec.created_at
  FROM engine_comparison ec,
       jsonb_array_elements(ec.disagree_flags) flag
 ORDER BY ec.created_at DESC
 LIMIT 200;
