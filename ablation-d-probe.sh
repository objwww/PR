#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== 全部 run 事件统计（top 12） ==='
Q "select left(e.run_id::text,8)||' | total='||e.c||' | chained='||e.ch from (select run_id, count(*) c, count(prev_hash) ch from rca_event group by run_id) e order by e.c desc limit 12"
echo '=== 82cf4cbf 与 4950d39b 明细 ==='
Q "select left(run_id::text,8)||' seq='||seq||' type='||event_type||' prev='||coalesce(left(prev_hash,8),'<null>') from rca_event where run_id in ('82cf4cbf-44fd-4ea0-9184-84115a53e9f0','4950d39b-2196-4028-9c7b-ee8f39e793b3') order by run_id, seq"
exit 0
