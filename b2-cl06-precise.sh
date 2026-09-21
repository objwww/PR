#!/bin/sh
# B2-CL06 精确核对：V101 部署（09-12 22:18Z）之后的多轮 run + 表结构
echo "== A) 部署后多轮 run =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select run_id, count(distinct round_id) rounds, count(*) calls, max(created_at) from rca_model_call where created_at > '2026-09-12 22:18:00+00' group by run_id having count(distinct round_id) > 1 order by max(created_at) desc"
echo "== B) rca_working_memory 结构 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c '\d rca_working_memory'
echo "== C) 部署后全部 run 的记忆行分布（按 run 聚合）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select run_id, count(*), count(parent_memory_id) with_parent, max(created_at) from rca_working_memory where created_at > '2026-09-12 22:18:00+00' group by run_id order by max(created_at) desc limit 15"
