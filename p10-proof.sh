#!/bin/sh
echo '--- order-arena F1 清偿日志:'
docker logs --since 30m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'F1 窗口内清偿|F1 恢复收口' | tail -6
echo '--- chaos-recovery 循环间隔:'
grep -n 'chaosIntervalMs\|chaos-recovery' /opt/build/pr/order-arena/src/main/java/com/objwww/pr/arena/ArenaConfig.java | head -4
echo '--- S4 会话故障类型（16:07 卡单 gauge=1 归属核验）:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select fault_type||' '||scenario_id||' '||state from arena.oa_chaos_session where created_at > now() - interval '40 minutes' order by created_at"
echo '--- p10 批案例结果:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' hit='||root_cause_hit from eval_case_result where eval_run_id='6564c6a6-5829-43b8-a76d-b8f849276ded' order by scenario_id"
