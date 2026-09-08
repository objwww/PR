-- M6-06 drain barrier v2（修正终态集：SUCCEEDED/FAILED/CANCELLED 均终态；
-- 佐证：41 条 CANCELLED 全带 finished_at；REPORTING 2 条=M6-02 E2E 影子 run
-- 设计终态（Am4ShadowTrigger 零发布收位点，其 task 全 DONE））
\echo '== B1 真在途 run（非终态且任一 task 未 DONE=M6-02 影子设计位已排除） =='
SELECT count(*) AS true_inflight_runs FROM rca_run r
 WHERE r.engine='HOLMES' AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
   AND EXISTS (SELECT 1 FROM rca_task t WHERE t.run_id=r.id AND t.state<>'DONE');
\echo '== B2 真在途 task（挂在非终态 run 上且未 DONE） =='
SELECT count(*) AS true_inflight_tasks FROM rca_task t
 JOIN rca_run r ON r.id=t.run_id
 WHERE r.engine='HOLMES' AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
   AND t.state <> 'DONE';
\echo '== B3 影子工作未收口 =='
SELECT count(*) AS unsettled_shadow_work FROM holmes_shadow_work
 WHERE state IN ('QUEUED','LEASED','FAILED');
\echo '== B4 fallback 未收口 =='
SELECT count(*) AS unsettled_fallbacks FROM run_fallback f
 JOIN rca_run fr ON fr.id=f.fallback_run_id
 WHERE fr.state NOT IN ('SUCCEEDED','FAILED');
\echo '== B5 豁免台账量化（非终态 task 按所属 run 终态分组=历史僵尸，无活认领） =='
SELECT r.state AS run_state, t.state AS task_state, count(*)
  FROM rca_task t JOIN rca_run r ON r.id=t.run_id
 WHERE r.engine='HOLMES' AND t.state<>'DONE' GROUP BY r.state, t.state;
\echo '== 恢复演练取材面：最旧 HOLMES SUCCEEDED 报告 =='
SELECT r.id AS run_id, rp.id AS report_id, r.created_at,
       octet_length(rp.package_json::text) AS pkg_bytes
  FROM rca_run r JOIN rca_report rp ON rp.run_id=r.id
 WHERE r.engine='HOLMES' AND r.state='SUCCEEDED'
 ORDER BY r.created_at ASC LIMIT 1;
