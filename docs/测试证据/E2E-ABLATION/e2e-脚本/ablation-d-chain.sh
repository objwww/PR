#!/bin/sh
# 消融D v2：run 4950d39b（78 行全链）seq=34 中段篡改
set -eu
RUN=4950d39b-2196-4028-9c7b-ee8f39e793b3
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
V="with c as (select seq, prev_hash, event_hash, lag(event_hash) over (order by seq) as prev_expected, encode(sha256(concat_ws(':',coalesce(lag(event_hash) over (order by seq),'GENESIS'),seq::text,event_type,payload_digest)::bytea),'hex') as recomputed from rca_event where run_id='$RUN' and prev_hash is not null)"

echo "=== [D-1] 全行备份 ==="
Q "drop table if exists rca_event_ablation_d_backup;"
Q "create table rca_event_ablation_d_backup as select * from rca_event where run_id='$RUN';"
echo "backup_rows=$(Q "select count(*) from rca_event_ablation_d_backup;")"

echo "=== [D-2] 篡改前基线 ==="
Q "$V select 'baseline=verified '||count(*) filter (where prev_hash=coalesce(prev_expected,'GENESIS') and event_hash=recomputed)||'/'||count(*)||' brokenAtSeq='||coalesce(min(seq) filter (where not (prev_hash=coalesce(prev_expected,'GENESIS') and event_hash=recomputed))::text,'-1') from c;"

echo "=== [D-3] 篡改 seq=34（payload+digest 同步重算，高级攻击者姿态） ==="
Q "update rca_event e set payload = n.p, payload_digest = encode(sha256(convert_to(n.p::text,'UTF8')),'hex') from (select payload::jsonb || '{\"tampered_by\":\"ablation-D\"}'::jsonb as p from rca_event where run_id='$RUN' and seq=34) n where e.run_id='$RUN' and e.seq=34;"
Q "select 'tampered=seq '||seq||' marked='||(payload::text like '%tampered_by%') from rca_event where run_id='$RUN' and seq=34;"

echo "=== [D-4a] 无链对照：仅 digest 对账 ==="
Q "select 'digest_check='||count(*) filter (where encode(sha256(convert_to(payload::text,'UTF8')),'hex')=payload_digest)||'/'||count(*)||' rows_consistent （全对上=篡改不可见）' from rca_event where run_id='$RUN';"

echo "=== [D-4b] 有链：全链重算 ==="
Q "$V select 'chain_check=brokenAtSeq='||coalesce(min(seq) filter (where not (prev_hash=coalesce(prev_expected,'GENESIS') and event_hash=recomputed))::text,'-1')||' verified='||count(*) filter (where prev_hash=coalesce(prev_expected,'GENESIS') and event_hash=recomputed)||'/'||count(*) from c;"

echo "=== [D-5] 还原并复验 ==="
Q "update rca_event e set payload = b.payload, payload_digest = b.payload_digest from rca_event_ablation_d_backup b where e.run_id='$RUN' and e.seq=b.seq;"
Q "$V select 'restored=verified '||count(*) filter (where prev_hash=coalesce(prev_expected,'GENESIS') and event_hash=recomputed)||'/'||count(*)||' brokenAtSeq='||coalesce(min(seq) filter (where not (prev_hash=coalesce(prev_expected,'GENESIS') and event_hash=recomputed))::text,'-1') from c;"
Q "drop table rca_event_ablation_d_backup;"
echo "backup_table_dropped=yes"
exit 0
