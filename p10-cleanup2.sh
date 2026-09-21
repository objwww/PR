#!/bin/sh
echo '=== [1] 删除手动实验 canonical 单（无外键引用，消除 F3 gauge 污染）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "delete from arena.oa_trade_order where correlation_id='chaos-manual-p10-drain-a' and booking_status='CREATED'"
echo '=== [2] 等 40s 让探针/规则循环回落 ==='
sleep 40
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=oa_stuck_orders_current' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('stuck_gauge=', max([float(r['value'][1]) for r in rs], default=0.0))"
echo '=== [3] 批终态 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='6564c6a6-5829-43b8-a76d-b8f849276ded'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' hit='||root_cause_hit from eval_case_result where eval_run_id='6564c6a6-5829-43b8-a76d-b8f849276ded' order by scenario_id"
