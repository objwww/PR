#!/bin/sh
# B2-CL06 既有跨轮 Run 盘点（只读）
echo "== 1) 按 round 数分布（action 序列跨 round 的 run）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select rounds, count(*) from (select run_id, count(distinct round_id) rounds from rca_model_call group by run_id) t group by rounds order by rounds desc"
echo "== 2) 多轮 run 明细（rounds>1，取最近 10 个）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select run_id, count(distinct round_id) rounds, count(*) calls, max(created_at)::date from rca_model_call group by run_id having count(distinct round_id) > 1 order by max(created_at) desc limit 10"
echo "== 3) 工作记忆表清单 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select table_name from information_schema.tables where table_schema='public' and table_name like '%memory%'"
echo "== 4) 工作记忆行分布 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select count(*), min(created_at)::date, max(created_at)::date from rca_working_memory" 2>/dev/null || echo "(表名待核)"
