#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---5 场景 alertname 事故状态---'
$PG "select incident_key||' '||status from incident where incident_key in (
'alertname=ArenaDuplicateOrders|service=order-arena|job=order-arena',
'alertname=ArenaIllegalTransitions|service=order-arena|job=order-arena',
'alertname=ArenaOrderStuck|service=order-arena|job=order-arena',
'alertname=ArenaPaymentOrderMismatch|service=order-arena|job=order-arena',
'alertname=ArenaPendingPaymentBacklog|service=order-arena|job=order-arena') order by incident_key;"
echo '---F1/F9 残留 gauge---'
$PG "select incident_key||' '||status from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
echo '---in-flight batches---'
$PG "select left(id::text,8)||' '||state||' '||coalesce(display_name,'?') from eval_run where state='RUNNING' and display_name is not null and display_name != '' order by created_at desc limit 3;"
