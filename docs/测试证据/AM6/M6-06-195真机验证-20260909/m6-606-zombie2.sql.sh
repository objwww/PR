set -e
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' <<'EOF'
\echo '== 非终态 task 按 run 终态分组（所属 run 是否已死） =='
SELECT r.state AS run_state, t.state AS task_state, count(*)
  FROM rca_task t JOIN rca_run r ON r.id=t.run_id
 WHERE r.engine='HOLMES' AND t.state <> 'DONE'
 GROUP BY r.state, t.state ORDER BY r.state, t.state;
\echo '== 2 条 REPORTING run 的 task 面 =='
SELECT r.id, r.state, t.state AS task_state, count(*)
  FROM rca_run r JOIN rca_task t ON t.run_id=r.id
 WHERE r.state='REPORTING' AND r.engine='HOLMES'
 GROUP BY r.id, r.state, t.state;
\echo '== scheduler slot 面（有无活认领者） =='
SELECT slot_scope, count(*), max(updated_at) AS latest FROM scheduler_slot GROUP BY slot_scope;
\echo '== 新 barrier SQL（修正终态集：SUCCEEDED/FAILED/CANCELLED） =='
SELECT
  (SELECT count(*) FROM rca_run WHERE engine='HOLMES'
     AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
     AND id NOT IN (SELECT run_id FROM rca_task WHERE state='DONE'
                      AND run_id IN (SELECT id FROM rca_run WHERE state='REPORTING'))) AS true_inflight_runs;
EOF
