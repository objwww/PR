#!/bin/sh
# E2E-M3-03 结构失败链 · 阶段二：断言 + 无条件恢复（holmes 换回 / stub 移除 / 路由删除）
# 协议: E2E|PASS/FAIL|检查名|详情；退出码 = FAIL 数。
# 断言面（技术方案 §12 E2E-M3-03 + §6.4 行 199）：REJECTED_* 调查记录同权落档 →
#   不出可发布报告 → 不发通知 → STRUCTURE_REJECTED 进分母（结构失败列，非超时列）。
set -u
TAG=am3-e2e03e-0906
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }
ck() {
  if [ "$2" = "$3" ]; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望=$2 实际=$3 $4"; fails=$((fails+1)); fi
}
ckz() {
  if [ "$3" -ge "$2" ] 2>/dev/null; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望≥$2 实际=$3 $4"; fails=$((fails+1)); fi
}
fails=0
RUN=$(q "select id from eval_run where dataset_version='$TAG' order by started_at desc limit 1")
# [M3-30 实测] AM0 分组语义（repeat_interval=4h + 同指纹连续性）抑制轮内短间隔重fire：
# r1 无投递→run_not_found（诚实缺席列）；r2 获得 run → 结构失败面。锚定 r2。
RCARUN=$(q "select coalesce(rca_run_id::text,'') from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and round_no=2 limit 1")

echo "== 断言（run=$RUN rca_run=$RCARUN） =="
ck "d00:批件锚定" "36" "${#RUN}" "run=$RUN"

v=$(q "select count(*) from rca_investigation_result where run_id='$RCARUN' and validation_status like 'REJECTED%'")
ckz "d01:REJECTED调查记录落档" 1 "$v" "rejected_results=$v"
v=$(q "select count(*) from rca_investigation_result where run_id='$RCARUN' and validation_status like 'REJECTED%' and execution_status='FAILED'")
ckz "d01b:执行终态FAILED" 1 "$v" "failed_terminal=$v"
v=$(q "select count(*) from rca_report where run_id='$RCARUN'")
ck "d02:无可发布报告行" "0" "$v" "reports=$v"
v=$(q "select count(*) from report_publication p join rca_report rr on p.report_id=rr.id where rr.run_id='$RCARUN'")
ck "d03:无publication" "0" "$v" "publications=$v"
v=$(q "select count(*) from notify_outbox o join rca_report rr on o.report_id=rr.id where rr.run_id='$RCARUN'")
ck "d04:无notify_outbox" "0" "$v" "outbox=$v"

v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and round_no=2 and verdict='STRUCTURE_REJECTED'")
ck "d05:结构失败轮(r2)=1" "1" "$v" "rejected_cases=$v"
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and round_no=1 and failure_sample::text like '%run_not_found%'")
ck "d05z:r1=AM抑制诚实缺席" "1" "$v" "r1_run_not_found=$v"
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and failure_sample::text like '%REJECTED_MALFORMED%'")
ckz "d05b:失败样本含验证状态" 1 "$v" "samples=$v"

v=$(q "select structure_rejected_count from eval_run where id='$RUN'")
ck "d06a:结构失败计1（进分母）" "1" "$v" "srej=$v"
v=$(q "select timeout_or_absent_count from eval_run where id='$RUN'")
ck "d06b:缺席列仅r1=1（REJECTED不入超时列）" "1" "$v" "to=$v"

before=$(grep -c "am3/testbot" /tmp/m330-e2e03-echo-before.json 2>/dev/null || echo 0)
after=$(docker run --rm --network alert-ab curlimages/curl:8.8.0 -s "http://echo-receiver:8080/__admin/requests" 2>/dev/null | grep -c "am3/testbot" || echo 0)
ck "d07:零通知（echo计数不变）" "$before" "$after" "before=$before after=$after"

v=$(q "select state from arena.oa_chaos_session where scenario_id like 'chaos-eval-$TAG-%' order by created_at desc limit 1")
ck "d08:靶场会话CLOSED" "CLOSED" "$v" "state=$v"

echo "== 恢复（无条件执行） =="
docker rm -f badmodel-stub 2>/dev/null && echo "stub removed" || echo "stub absent"
MK=$(grep '^LITELLM_MASTER_KEY=' /opt/build/pr/deploy/alert/.env | cut -d= -f2)
curl -s -X POST http://127.0.0.1:4100/model/delete -H "Authorization: Bearer $MK" \
  -H 'Content-Type: application/json' -d '{"model_name":"badmodel"}' | head -c 200; echo
docker rm -f holmesgpt-am1 2>/dev/null || true
docker rename holmesgpt-am1-hold holmesgpt-am1 2>/dev/null || echo "hold rename skipped"
docker start holmesgpt-am1 2>/dev/null || true
sleep 8
docker ps --filter name=holmesgpt-am1 --format '{{.Names}} {{.Status}}'
docker logs holmesgpt-am1 --since 1m 2>&1 | tail -2

echo "E2E|SUBTOTAL|e2e03|fail=$fails"
exit $fails
