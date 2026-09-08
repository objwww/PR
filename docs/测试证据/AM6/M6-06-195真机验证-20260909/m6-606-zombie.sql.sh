set -e
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' <<'EOF'
\echo '== 非终态 HOLMES run 分态/代际/年龄 =='
SELECT state, generation, count(*), min(created_at) AS oldest, max(created_at) AS newest
  FROM rca_run WHERE engine='HOLMES' AND state NOT IN ('SUCCEEDED','FAILED')
 GROUP BY state, generation ORDER BY state, generation;
\echo '== 非终态 HOLMES run 明细（新近 10 条） =='
SELECT id, state, trigger_kind, generation, created_at, finished_at,
       left(coalesce(last_error::text,''), 40) AS err
  FROM rca_run WHERE engine='HOLMES' AND state NOT IN ('SUCCEEDED','FAILED')
 ORDER BY created_at DESC LIMIT 10;
\echo '== 非终态 task 状态分布 =='
SELECT t.state, count(*) FROM rca_task t
 JOIN rca_run r ON r.id=t.run_id
 WHERE r.engine='HOLMES' AND t.state <> 'DONE' GROUP BY t.state;
\echo '== 是否有 NATIVE 非终态（对照面） =='
SELECT engine, state, count(*) FROM rca_run
 WHERE state NOT IN ('SUCCEEDED','FAILED') GROUP BY engine, state;
EOF
