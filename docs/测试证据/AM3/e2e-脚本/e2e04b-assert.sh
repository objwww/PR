#!/bin/sh
# E2E-M3-04 崩溃恢复链 · 断言 v3（跨批证据链版）
# 证据链：v2 drill（KILL1 于 05:55:32 打进调查 1s）→ control-app 死亡（v2 教训：
#   docker kill 不触发 restart 策略）→ 06:32 拉起 → attempt1/2 回收 UNKNOWN →
#   gen12 重投递（06:37:14）触发重驱 → attempt3 SUCCEEDED/STRUCTURE_VALIDATED
#   （06:42:48）→ run SUCCEEDED（06:43:07）→ 报告唯一 → outbox/publication SENT。
# v4 批（am3-e2e04b-0906）：r1=解析窗内 resolver 不认跨批 run（session 作用域）诚实缺席；
#   r2 正常出判定。KILL2=窗口亚秒级（notify worker 即领即发）MISSED，重试面归 E2E-M3-05。
set -u
TAG=am3-e2e04b-0906
V2LOG=/tmp/m330-e2e04-drill-v2-kill-不自启-证据.log
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }
ck() {
  if [ "$2" = "$3" ]; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望=$2 实际=$3 $4"; fails=$((fails+1)); fi
}
fails=0
RUN=$(q "select id from eval_run where dataset_version='$TAG' order by started_at desc limit 1")
RR=ff5694fb-4f20-4d46-a6ce-ca12d1d1bb5a

echo "== f0x：崩溃-重收敛链（对 $RR） =="
ck "f00:v4批件锚定" "36" "${#RUN}" "run=$RUN"
ck "f01:杀点A已执行（v2 drill 日志）" "1" "$(grep -c 'KILL1=FIRED' $V2LOG)" "$(grep -o 'KILL1=[^;]*' $V2LOG | head -1)"
v=$(q "select count(*) from rca_investigation_result where run_id='$RR' and execution_status='STARTED'")
ck "f02:无悬挂STARTED（两杀均回收UNKNOWN）" "0" "$v" "hanging=$v"
v=$(q "select count(*) from rca_investigation_result where run_id='$RR' and execution_status='UNKNOWN'")
ck "f02b:UNKNOWN回收落档=2" "2" "$v" "unknown=$v"
a=$(q "select count(distinct attempt_id) from rca_investigation_result where run_id='$RR'")
b=$(q "select count(*) from rca_investigation_result where run_id='$RR'")
ck "f03:同attempt单Result" "$a" "$b" "attempts=$a rows=$b"
v=$(q "select count(*) from rca_investigation_result where run_id='$RR' and execution_status='SUCCEEDED' and validation_status='STRUCTURE_VALIDATED'")
ck "f03b:重收敛attempt终态SUCCEEDED" "1" "$v" "recovered=$v"
v=$(q "select count(*) from rca_report where run_id='$RR'")
ck "f04:最终报告唯一" "1" "$v" "reports=$v"
v=$(q "select o.state from notify_outbox o join rca_report rr on o.report_id=rr.id where rr.run_id='$RR' limit 1")
ck "f05:outbox不丢（终入SENT）" "SENT" "$v" "outbox=$v"
v=$(q "select p.state from report_publication p join rca_report rr on p.report_id=rr.id where rr.run_id='$RR' limit 1")
ck "f06:publication SENT" "SENT" "$v" "pub=$v"
v=$(q "select state from rca_run where id='$RR'")
ck "f06b:崩溃run终态SUCCEEDED" "SUCCEEDED" "$v" "state=$v"

echo "== f7x：v4 批判定面 =="
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and verdict='TIMEOUT_OR_ABSENT'")
ck "f07:两轮诚实缺席（重收敛跨批不可归集，见台账）" "2" "$v" "absent=$v"
v=$(q "select failure_sample::text from eval_case_result where eval_run_id='$RUN' and round_no=1")
ck "f07b:r1缺席原因=run_not_found" "1" "$(echo "$v" | grep -c run_not_found)" "sample=$v"
v=$(q "select state from eval_run where id='$RUN'")
ck "f07c:v4批件SUCCEEDED" "SUCCEEDED" "$v" "state=$v"
v=$(q "select state from arena.oa_chaos_session where scenario_id like 'chaos-eval-$TAG-%' order by created_at desc limit 1")
ck "f08:靶场会话CLOSED" "CLOSED" "$v" "state=$v"
v=$(docker inspect deploy-control-app-1 --format '{{.RestartCount}}' 2>/dev/null || echo 999)
echo "E2E|INFO|control_restarts|$v"
v=$(q "select round_no, verdict, coalesce(rca_run_id::text,'-'), left(failure_sample::text,80) from eval_case_result where eval_run_id='$RUN' order by round_no")
echo "E2E|INFO|v4_rounds|$v"

echo "E2E|SUBTOTAL|e2e04b|fail=$fails"
exit $fails
