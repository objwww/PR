#!/bin/sh
echo '=== ArenaOrderStuck 已解决事故与其终态报告 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select i.id||' | '||i.incident_key||' | '||i.status
  from incident i
 where i.incident_key like 'alertname=ArenaOrderStuck%'
 order by i.episode_started_at desc limit 3;"
echo '---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select r.id||' | run_state='||r.state||' | report='||rp.id||' | '||rp.validation_status
  from rca_run r
  join incident i on i.id = r.incident_id
  join rca_report rp on rp.run_id = r.id
 where i.incident_key like 'alertname=ArenaOrderStuck%'
   and r.state = 'SUCCEEDED'
 order by r.created_at desc limit 3;"
echo '=== 该报告的证据数（准入契约：零证据拒绝）==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select count(*) || ' evidence rows'
  from rca_evidence e
 where e.run_id in (
   select r.id from rca_run r
     join incident i on i.id = r.incident_id
    where i.incident_key like 'alertname=ArenaOrderStuck%'
      and r.state = 'SUCCEEDED'
    order by r.created_at desc limit 1);"
echo '=== 该事故的 claims（GT 依据：人工按已核实结论给定）==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select c.claim_type||' | '||c.status||' | '||left(coalesce(c.reason,''),80)
  from rca_claim c
 where c.run_id in (
   select r.id from rca_run r
     join incident i on i.id = r.incident_id
    where i.incident_key like 'alertname=ArenaOrderStuck%'
      and r.state = 'SUCCEEDED'
    order by r.created_at desc limit 1)
   and c.lifecycle = 'ACTIVE';"
echo '=== alert_inbox 冻结 ArenaOrderStuck firing 载荷 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select count(*) || ' firing payloads, latest ' || coalesce(max(received_at)::text,'-')
  from alert_inbox
 where state in ('PROCESSED','IGNORED','RECEIVED')
   and convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%'
   and convert_from(payload_raw,'UTF8') like '%\"status\":\"firing\"%';"
