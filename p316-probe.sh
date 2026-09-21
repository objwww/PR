#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- usage 样例:'
P "select usage, pricing_version, currency from rca_model_call where cost_micros is not null order by created_at desc limit 2;"
echo '--- costs by model 24h:'
P "select requested_model, count(*), round(sum(cost_micros)/1000000.0,4) as cny from rca_model_call where created_at >= now() - interval '24 hours' and cost_micros is not null group by 1 order by 3 desc;"
echo '--- rca_task 终态耗时口径样例(ready_since null 比例):'
P "select count(*) filter (where ready_since is null), count(*) from rca_task where state in ('DONE','DEAD','CANCELLED');"
