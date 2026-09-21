#!/bin/sh
# RR22 诊断第二轮：容器时间线 + 逐案 DB 真行 + 应用日志 + 执行器 SQL 面
echo ==container-lifecycle==
docker inspect rriso-control-app-1 --format 'StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}}'
echo ==executor-out-now==
tail -15 /tmp/rr2224.out
echo ==executor-modelcase-logic==
sed -n '40,80p' /opt/build/rr2224-run.sh
echo ==episodes-recent==
docker exec rriso-postgres-1 psql -U pr -d pr -t -c "
select e.fingerprint, e.created_at, r.id as run_id, r.state
from alert_inbox e left join rca_run r on r.alert_episode_id = e.id
where e.created_at > now() - interval '90 minutes'
order by e.created_at;" 2>/dev/null | tail -12
echo ==model-calls-recent==
docker exec rriso-postgres-1 psql -U pr -d pr -t -c "
select mc.created_at, mc.state, mc.reason_code, mc.error_code, r.id
from rca_model_call mc join rca_run r on r.id = mc.run_id
where mc.created_at > now() - interval '90 minutes'
order by mc.created_at;" 2>/dev/null | tail -30
echo ==applog-model-activity==
docker logs rriso-control-app-1 --since 40m 2>&1 | grep -iE 'circuit|defer|retry|429|slow|chat/completions|gateway' | tail -25
