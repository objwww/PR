#!/bin/sh
echo '=== ae277bae 失败原因 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select terminal_reason from eval_run where id='ae277bae-560a-440d-9323-1c964f07ce0a';"
echo '=== eed0e10d 旧 worker 执行到哪了 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||entered_at||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a' order by entered_at;"
echo '=== 已写入案例行 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict from eval_case_result where eval_run_id in ('eed0e10d-e020-4f3c-abbc-161cbc166d1a','ae277bae-560a-440d-9323-1c964f07ce0a');"
echo '=== 杀陈旧 worker ==='
docker stop -t 5 eval-runner-ds1 2>&1 || docker rm -f eval-runner-ds1 2>&1
docker rm -f eval-runner-ds1 2>/dev/null || true
docker ps --format '{{.Names}}' | grep -i eval || echo '(仅剩 p2replay worker)'
echo '=== S1 flag 残留核查（paymentFailure 当前 variant）==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 1;" >/dev/null
grep -A 3 'paymentFailure' /opt/build/opentelemetry-demo/src/flagd/demo.flagd.json | head -8
echo '=== checkout 告警 incident 状态 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select i.status||' | '||i.incident_key from incident i where i.incident_key like 'alertname=checkout%' or i.incident_key like '%checkout%' order by i.episode_started_at desc limit 3;"
