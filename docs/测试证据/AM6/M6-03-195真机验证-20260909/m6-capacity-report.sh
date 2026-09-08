#!/bin/sh
# ============================================================================
# m6-capacity-report.sh —— M6-03 容量报告（E2E-AM6-03 面一；[195] 宿主执行）
#
# 内容（落码方案 M6-03：Native 实跑预算/P95/内存 vs Holmes 同时段同分布 control
#       对比 + 双方绝对 SLO 检查（E-20，禁 before/after））：
#   §1 HOST1 内存面（架构 v1.2 :946-953 三档判定 + 滞回）
#   §2 容器 RSS/limit（架构 §12.2 资源账本对照）
#   §3 引擎同窗对照（复用 m6-engine-observation.sql：预算/延迟/错误率/对照进度；
#      双引擎同窗并列，禁 before/after）
#   §4 队列积压（in-flight + oldest-ready-age 绝对值——架构 §15.3 系统运行 SLO 面）
#   §5 绝对 SLO 检查（仅对已冻结值判定：内存三档阈值、holmes P95 vs LLM gateway
#      总时限 300000ms（application.yml 冻结面）；无冻结值的维度只报绝对值不判
#      PASS/FAIL——诚实边界）
#
# 安全纪律：纯只读（free/docker stats/psql SELECT）；零密钥输出；SQL 全部经
#   stdin 注入容器内 psql（无引号嵌套面）。
# 执行位：/opt/build/pr/deploy/policy/ 下 sh m6-capacity-report.sh
# ============================================================================
set -e
PSCMD="docker exec -i deploy-postgres-1 sh -c 'psql -U \$POSTGRES_USER -d \$POSTGRES_DB -v ON_ERROR_STOP=1 -At'"

psql_stdin() {
    # 用法：psql_stdin <<'SQL' ... SQL（SQL 经 stdin 直达容器内 psql，只读语句）
    eval "$PSCMD"
}

echo '== §1 HOST1 内存面（架构 :946-953 三档判定）=='
free -m | sed -n '1,3p'
swapon --show 2>/dev/null || true
AVAIL_MB="$(free -m | awk '/^Mem:/{print $7}')"
TIER=L0
if [ "$AVAIL_MB" -lt 512 ]; then TIER=L3; elif [ "$AVAIL_MB" -lt 768 ]; then TIER=L2
elif [ "$AVAIL_MB" -lt 1229 ]; then TIER=L1; fi
echo "mem_available_mb=${AVAIL_MB} classified_tier=${TIER}"
echo 'thresholds: L1<1229MiB(1.2GiB,持续5min) L2<768MiB(持续2min) L3<512MiB; 恢复滞回=连续10min>1536MiB逐级'
case "$TIER" in
    L0) echo 'tier_action: 无（正常态）' ;;
    L1) echo 'tier_action: 停新 Live E2E/在线 Shadow + 关 order-arena + slot 2→1（自动触发面现状见缺口台账）' ;;
    L2) echo 'tier_action: 入口继续原子持久化 + 暂停领取新 RCA Task + 独立值班告警' ;;
    L3) echo 'tier_action: readiness fail + 只允许恢复任务与受控运维' ;;
esac

echo ''
echo '== §2 容器 RSS/limit（架构 §12.2 资源账本对照；缺位容器自动略行）=='
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}' 2>/dev/null \
    | grep -E 'control-app|postgres|holmesgpt|prometheus|alertmanager|notify|order-arena|chaos-admin|litellm|gatus' || true

echo ''
echo '== §3 引擎同窗对照（预算/延迟/错误率/对照进度；双引擎同窗并列禁 before/after）=='
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1' \
    < /opt/build/pr/deploy/policy/m6-engine-observation.sql

echo ''
echo '== §4 队列积压（架构 §15.3 系统运行 SLO 面：绝对值报告）=='
psql_stdin <<'SQL'
SELECT 'in_flight=' || count(*) FILTER (WHERE state IN ('QUEUED','RUNNING','REPORTING'))
       || ' oldest_ready_age='
       || coalesce((max(now()-started_at) FILTER (WHERE state IN ('QUEUED','RUNNING','REPORTING')))::text, 'none')
FROM rca_run;
SQL

echo ''
echo '== §5 绝对 SLO 检查（仅对已冻结值判定）=='
echo '[SLO-1] 内存三档（架构 :946-953 冻结值）:'
if [ "$TIER" = "L0" ]; then
    echo "  PASS mem_available=${AVAIL_MB}MiB >= L1 阈值 1229MiB"
else
    echo "  HIT tier=${TIER} mem_available=${AVAIL_MB}MiB"
fi
echo '[SLO-2] holmes P95 延迟 vs LLM gateway 总时限 300000ms（application.yml 冻结面，近7日窗）:'
psql_stdin <<'SQL'
SELECT 'holmes_p95_ms=' || coalesce(round((percentile_cont(0.95) WITHIN GROUP
    (ORDER BY (extract(epoch FROM (finished_at-started_at))*1000)))::numeric, 1)::text, 'sample_null')
FROM rca_run
WHERE engine='HOLMES' AND finished_at IS NOT NULL AND started_at > now()-interval '7 days';
SQL
echo '[SLO-3] native P95 延迟（近7日窗；无冻结阈值——只报绝对值）:'
psql_stdin <<'SQL'
SELECT 'native_p95_ms=' || coalesce(round((percentile_cont(0.95) WITHIN GROUP
    (ORDER BY (extract(epoch FROM (finished_at-started_at))*1000)))::numeric, 1)::text, 'sample_null')
FROM rca_run
WHERE engine='NATIVE' AND finished_at IS NOT NULL AND started_at > now()-interval '7 days';
SQL
echo '[SLO-4] token 预算：per-call/per-step 冻结面（100000/16000/120000）作用于 LLM 单步；per-report 汇总值无冻结阈值——§3 预算段只报绝对值'
echo '[SLO-5] MemAvailable 观测面：node_exporter 缺失（P7 B1）→ Prometheus 采不到宿主内存，三档闸的持续触发窗（5min/2min）无自动判定器（缺口，见内存闸演练记录）'
echo '== 容量报告完 =='
