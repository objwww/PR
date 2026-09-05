#!/bin/sh
# E2E 全程编排（M2-25~28）：F1→F2→F3 串行（每场景 注入→指标→告警→incident→Holmes
# 报告→off→修复→归零 + manifest 关联）→ TTL 崩溃自愈演练 → DP-C02。证据落
# docs/测试证据/AM2/。注意：本脚本在 195 宿主执行（bash e2e_runall.sh 需先 setup）。
set -u
EV="/opt/build/pr/docs/测试证据/AM2"
LOG="$EV/e2e-三场景-run.log"
EXEC="docker exec arena-e2e-cli"
mkdir -p "$EV"
rm -f "$EV/.phase_failed" /tmp/e2e_ids_F1.txt /tmp/e2e_ids_F2.txt /tmp/e2e_ids_F3.txt

# 退出码必须落盘（{...} | tee 是子壳，变量传不出来）
mark_fail() { echo 1 > "$EV/.phase_failed"; }

# 同轮 run 的 tag（scenario_id 全局唯一，重跑必须换名；driver 两阶段共读 /e2e/tag）
TAG=$(date -u +%m%d%H%M%S)
$EXEC sh -c "echo $TAG > /e2e/tag"
echo "# run tag=$TAG"

{
  echo "# AM2 M2-25~28 三场景 E2E @ $(date -u '+%FT%TZ') (slot=1 串行)"
  echo
  echo "======== preflight（DP-C04 管理面鉴权面 + 链路可达） ========"
  $EXEC python3 /e2e/driver.py preflight
  PF=$?
  echo "（preflight 退出码=$PF）"
  [ $PF -ne 0 ] && mark_fail

  for F in F1 F2 F3; do
    case $F in
      F1) AN=ArenaDuplicateOrders ;;
      F2) AN=ArenaIllegalTransitions ;;
      F3) AN=ArenaOrderStuck ;;
    esac
    echo
    echo "======== 场景 $F · phase1（激活→注入→指标→告警→指纹三重对账） ========"
    $EXEC python3 /e2e/driver.py phase1 "$F"
    P1=$?
    echo "（$F phase1 退出码=$P1）"
    [ $P1 -ne 0 ] && mark_fail
    echo "-------- $F · incident/Holmes 侦听（alertname=$AN） --------"
    sh /tmp/watch_incident.sh "$AN" 720 "/tmp/e2e_ids_$F.txt"
    W=$?
    if [ $W -ne 0 ]; then
      echo "E2E|FAIL|$F incident/报告侦听|watch 退出码=$W"
      continue
    fi
    read -r INC GEN RUN REP < "/tmp/e2e_ids_$F.txt"
    echo "-------- $F · phase2（回填→off→修复→归零；incident=$INC run=$RUN report=$REP） --------"
    $EXEC python3 /e2e/driver.py phase2 "$F" "$INC" "$GEN" "$RUN" "$REP"
    P2=$?
    echo "（$F phase2 退出码=$P2）"
    [ $P2 -ne 0 ] && mark_fail
    echo "-------- $F manifest 关联（GT/Alert/Incident/Report 一一对应） --------"
    echo "  alert=$AN（激活指纹见上方 p1:activation/p1:指纹三重一致）"
    echo "  incident_id=$INC incident_generation=$GEN"
    echo "  run_id=$RUN report_id=$REP"
  done

  echo
  echo "======== TTL 崩溃自愈演练（无操作员，DP-C07） ========"
  $EXEC python3 /e2e/driver.py ttl
  TT=$?
  echo "（ttl 退出码=$TT）"
  [ $TT -ne 0 ] && mark_fail

  echo
  echo "======== DP-C02 live- 正常流量面 ========"
  $EXEC python3 /e2e/driver.py dpc02
  DC=$?
  echo "（dpc02 退出码=$DC）"
  [ $DC -ne 0 ] && mark_fail

  echo
  echo "======== DP-C01/03/06 部署门取证 ========"
  echo "-- 容器健康（DP-C01） --"
  docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'order-arena|arena-chaos-admin|arena-migrate'
  echo "-- 网络成员（DP-C03，C-3 断言：arena 只在 alert-net、admin 只在 eval-mgmt） --"
  docker inspect alert-order-arena-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
  echo
  docker inspect alert-arena-chaos-admin-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
  echo
  docker inspect deploy-postgres-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
  echo
  echo "-- admin 宿主端口为零（M2-17） --"
  docker port alert-arena-chaos-admin-1 | wc -l
  echo "-- 迁移版本（DP-C06） --"
  docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc \
    "SELECT installed_rank||' v'||version||' success='||success FROM arena.flyway_schema_history ORDER BY installed_rank"

  echo
  echo "======== 落库取证（会话/审计/事件/scenario_map/incident/报告） ========"
  docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c \
    "SELECT s.scenario_id, s.fault_type, s.target, s.state, s.generation FROM arena.oa_chaos_session s ORDER BY s.created_at" \
    -c "SELECT s.scenario_id, a.fault_type, a.action, coalesce(a.order_id::text,'-') AS order_id, a.detail FROM arena.oa_injection_audit a JOIN arena.oa_chaos_session s ON s.id=a.session_id ORDER BY a.occurred_at" \
    -c "SELECT s.scenario_id, e.event_type, e.occurred_at FROM arena.oa_chaos_event e JOIN arena.oa_chaos_session s ON s.id=e.session_id ORDER BY e.occurred_at" \
    -c "SELECT scenario_id, mapping_version, alert_fingerprint, incident_id, incident_generation, run_id, report_id FROM arena.oa_scenario_map ORDER BY scenario_id, mapping_version" \
    -c "SELECT incident_key, status, generation, current_rca_run_id FROM incident WHERE incident_key LIKE 'alertname=Arena%' ORDER BY created_at" \
    -c "SELECT r.incident_id, r.state, r.trigger_kind FROM rca_run r WHERE r.incident_id IN (SELECT id FROM incident WHERE incident_key LIKE 'alertname=Arena%') ORDER BY r.created_at" \
    -c "SELECT p.run_id, p.validation_status, p.model FROM rca_report p WHERE p.run_id IN (SELECT id FROM rca_run WHERE incident_id IN (SELECT id FROM incident WHERE incident_key LIKE 'alertname=Arena%')) ORDER BY p.created_at"

  echo
  echo "# run finished @ $(date -u '+%FT%TZ')"
} 2>&1 | tee "$LOG"

if [ -f "$EV/.phase_failed" ] || grep -q "E2E|FAIL|" "$LOG" || grep -q "Traceback" "$LOG"; then
  echo "RESULT: FAIL（某阶段退出码非零 / 存在 E2E|FAIL 行 / 出现 Traceback）"
  exit 1
fi
echo "RESULT: ALL PASS"
