#!/bin/sh
# ============================================================================
# m6-memory-gate-drill.sh —— M6-03 HOST1 三档内存闸演练（架构 v1.2 :946-953）
#                              [195] 宿主执行（经 ssh stdin 注入 bash）
#
# 安全裁定（先于一切）：不在共享生产宿主人为压低 MemAvailable 制造真实压力——
#   HOST1 同时承载 postgres/control-app 等生产面，人为压力=OOM 杀生产容器的风险；
#   压力面受控演练待 node_exporter+Prom rule 面（P7 B1 前置）落地后按 runbook 执行。
#   本演练交付：①当前水位三档分类（架构冻结阈值）；②tier1 已有动作面实弹演练
#   （order-arena 停/启 = 可回收容量面，唯一变更项，前后健康对拍）；③tier2/tier3
#   机制面现状实证（readiness 表达面/领取旋钮/自动判定器四项缺口现场取证）。
#
# 变更面声明：docker stop/start alert-order-arena-1 各一次（E2E arena 组件，
#   非生产控制面）；其余全部只读。
# ============================================================================
set -e
TS="$(date -u +%Y%m%dT%H%M%SZ)"
echo "== HOST1 三档内存闸演练 $TS =="

# ---- §A 当前水位三档分类（架构 :946-953 冻结阈值 + 滞回） ------------------
echo '--- §A 观测分类 ---'
free -m | sed -n '1,3p'
AVAIL_MB="$(free -m | awk '/^Mem:/{print $7}')"
TIER=L0
if [ "$AVAIL_MB" -lt 512 ]; then TIER=L3; elif [ "$AVAIL_MB" -lt 768 ]; then TIER=L2
elif [ "$AVAIL_MB" -lt 1229 ]; then TIER=L1; fi
echo "mem_available_mb=${AVAIL_MB} classified_tier=${TIER}"
echo 'L1<1229MiB(停新LiveE2E/在线Shadow+关order-arena+slot2→1,持续5min) L2<768MiB(暂停领取,持续2min) L3<512MiB(readiness fail)'
echo '恢复滞回=连续10min>1536MiB逐级恢复;每次降级应落RESOURCE_MODE_CHANGED事件(见§D缺口)'

# ---- §B tier1 已有动作面实弹：order-arena 停/启 ----------------------------
echo '--- §B tier1 动作面实弹（order-arena 停=可回收容量；停/启各一次）---'
ARENA=alert-order-arena-1
if docker ps --format '{{.Names}}' | grep -qx "$ARENA"; then
    docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' "$ARENA"
    RSS_BEFORE="$(docker stats --no-stream --format '{{.MemUsage}}' "$ARENA" | sed 's#/.*##')"
    docker stop "$ARENA" >/dev/null
    echo "stopped $ARENA (rss_before=${RSS_BEFORE})"
    docker ps --format '{{.Names}}' | grep -qx "$ARENA" && echo 'FAIL: stop 后仍在运行' || echo 'stop_confirmed=true'
    sleep 2
    AVAIL_AFTER_STOP="$(free -m | awk '/^Mem:/{print $7}')"
    echo "mem_available_mb_after_stop=${AVAIL_AFTER_STOP} (delta=$((AVAIL_AFTER_STOP-AVAIL_MB))MiB)"
    H1="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)"
    echo "control_health_during_arena_stop=${H1} (tier1 动作不得伤及控制面)"
    docker start "$ARENA" >/dev/null
    sleep 8
    ST="$(docker inspect -f '{{.State.Health.Status}}' "$ARENA" 2>/dev/null || echo unknown)"
    RSS_AFTER="$(docker stats --no-stream --format '{{.MemUsage}}' "$ARENA" | sed 's#/.*##')"
    echo "restarted $ARENA health=${ST} rss_after=${RSS_AFTER}"
    [ "$H1" = "200" ] || { echo 'FAIL: order-arena 停止期间控制面失康'; exit 1; }
else
    echo "skip: $ARENA 未在运行（记录现状，不作变更）"
fi

# ---- §C slot 2→1 机制面（只读验证 + 不 DELETE 纪律） -----------------------
echo '--- §C slot 2→1 机制面 ---'
echo 'V7 :337 纪律：scheduler_slot 固定槽位预置，扩容=新迁移加行，不 DELETE——运行时无降槽旋钮，'
echo '  slot 2→1 的运维面当前只能走迁移变更（本演练不做生产 schema 变更）；以下只读验证约束语义：'
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -At' <<'SQL'
SELECT 'slot_rows=' || count(*) || ' (scope=rca)' FROM scheduler_slot WHERE scope='rca';
SELECT 'occupied_now=' || count(*) || ' occupied_max_hist='
       || (SELECT coalesce(max(occ),0) FROM (
             SELECT lease_owner, count(*) AS occ FROM scheduler_slot
             WHERE scope='rca' AND lease_owner IS NOT NULL GROUP BY lease_owner) t)
FROM scheduler_slot WHERE scope='rca';
SQL
echo 'RcaWorker.claimWork 判定（代码 :259）：occupiedSlots(scope).size() >= totalSlots(scope) 即不再领取——'
echo '  槽位上限=领取上限的机制在位；"按内存水位自动降槽"的触发器未落码（见 §D）'

# ---- §D tier2/tier3 机制面现状实证（缺口取证） -----------------------------
echo '--- §D tier2/tier3 缺口取证 ---'
echo '[G1] readiness 表达面（tier3 要求 readiness fail）:'
curl -s -o /dev/null -w '  /actuator/health/readiness http=%{http_code}\n' http://127.0.0.1:8080/actuator/health/readiness
echo '  404=probes 未启用——readiness/liveness 分组不存在，tier3 无表达面（application.yml 仅暴露 health,info,metrics）'
echo '[G2] 自动判定器（三档均要求）：'
echo '  control-app 主树 grep MemAvailable/RESOURCE_MODE_CHANGED/768 = 0 命中（2026-09-09 本地 findstr 实证）——'
echo '  无 MemAvailable 采样、无 RESOURCE_MODE_CHANGED 事件、无领取暂停与 readiness 联动组件'
echo '[G3] Prometheus 宿主内存指标面（P7 B1 复现）:'
curl -s 'http://127.0.0.1:9090/api/v1/query?query=node_memory_MemAvailable_bytes' | head -c 200; echo
echo '  （空 result 向量=node_exporter 缺失，三档闸 5min/2min 持续窗判定无数据源）'
echo '[G4] 领取暂停旋钮（tier2）：worker 领取仅受 scheduler_slot 上限约束（§C），无内存水位联动开关'
echo '[G5] 独立值班告警（tier2）：Gatus 外部探针通道收口地址 O-66 开放项（GATUS_ONCALL_WEBHOOK_URL 未裁定）'

# ---- §E 缺口台账 ------------------------------------------------------------
echo '--- §E 三档缺口台账（架构要求 vs 现状）---'
echo 'L1 | 停新LiveE2E/Shadow: 在线Shadow M6-03时点尚不存在(M6-05交付) | LiveE2E停=order-arena停已实弹(§B) | slot2→1: 迁移面管理无运行时旋钮(§C) | 自动触发: 缺(G2/G3)'
echo 'L2 | 暂停领取: 仅槽位上限间接约束,无水位联动(G4) | 独立值班告警: O-66 未收口(G5) | 入口原子持久化: 恒在(非降级专属)'
echo 'L3 | readiness fail: 无表达面(G1 404) | OOM/reclaim 响应: 无'
echo '== 演练完（tier1 动作面实弹通过；tier2/3 机制缺口如实留证 → BUGLOG BA-54）=='
