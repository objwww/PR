#!/bin/sh
echo '=== 挑选 span 最丰富的 run（模型+工具行最多） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select r.id||'|'||r.state||'|模型'||coalesce(m.c,0)||'|工具'||coalesce(t.c,0)||'|尝试'||coalesce(a.c,0)
from rca_run r
left join (select run_id, count(*) c from rca_model_call group by run_id) m on m.run_id=r.id
left join (select run_id, count(*) c from rca_tool_invocation group by run_id) t on t.run_id=r.id
left join (select t2.run_id, count(*) c from rca_attempt a2 join rca_task t2 on t2.id=a2.task_id group by t2.run_id) a on a.run_id=r.id
where coalesce(m.c,0)+coalesce(t.c,0) > 0
order by coalesce(m.c,0)+coalesce(t.c,0) desc
limit 5;"
echo '=== 事件锚点数量（该 run） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select run_id, count(*) from rca_event group by run_id order by count(*) desc limit 3;"
