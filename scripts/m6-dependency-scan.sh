#!/bin/sh
# ============================================================================
# m6-dependency-scan.sh —— M6-06 Holmes 退场六面依赖扫描（落码方案 M6-06①）
#
# 用法（195）：sh scripts/m6-dependency-scan.sh > /tmp/m6-606-scan.log 2>&1
# 环境要求：REPO_ROOT（默认 /opt/projects/pr_agent_it）+ alert/deploy 两个
#           compose 项目在案。密钥面只列键名不打印值（⑥ 密钥纪律）。
# 每项输出 = 依赖清单 + 处置裁定（删 / 留 / 豁免留痕）。
# ============================================================================
set -e
REPO="${REPO_ROOT:-/opt/projects/pr_agent_it}"
ALERT_COMPOSE="$REPO/deploy/alert/docker-compose.yml"
DEPLOY_COMPOSE="$REPO/deploy/docker-compose.yml"

echo '================================================================'
echo '面① 代码引用（main 树 *.java 含 holmes 字样，类名/注释分账）'
echo '================================================================'
grep -ril holmes --include='*.java' \
    "$REPO/control-app/src/main/java" "$REPO/notify-app/src/main/java" 2>/dev/null \
  | sort > /tmp/scan-face1.txt || true
wc -l < /tmp/scan-face1.txt | xargs echo 'main 树命中文件数:'
cat /tmp/scan-face1.txt
echo '--- 生产装配/执行面引用（M6-07 摘除对象裁定） ---'
grep -rn 'HolmesClient\|HolmesInvestigationExecutor\|holmesInvestigationExecutor\|holmesClient' \
    --include='*.java' "$REPO/control-app/src/main/java" 2>/dev/null || echo '  (无)'
echo '裁定: holmesClient/holmesInvestigationExecutor bean+铸造点 HOLMES 分支=删;'
echo '      RcaEngine.HOLMES/Digest 历史读面注释/Am4ShadowTrigger(E2E 入口)=留(豁免:历史行+演练面);'

echo '================================================================'
echo '面② compose 服务/network/volume'
echo '================================================================'
echo "--- alert compose（holmesgpt 所在项目）服务清单:"
grep -nE '^  [a-z][a-z0-9-]*:' "$ALERT_COMPOSE" | sed 's/://'
echo "--- holmesgpt 段:"
grep -n -A 20 'holmesgpt:' "$ALERT_COMPOSE" | head -30 || echo '  (无 holmesgpt 段)'
echo "--- deploy compose holmes 引用（HOLMES_BASE_URL/HOLMES_API_KEY 映射）:"
grep -n 'HOLMES' "$DEPLOY_COMPOSE" || echo '  (无)'
echo '裁定: alert compose holmesgpt 服务+其卷/网段=删; litellm=留(C-63:Native 模型调用同经此路由);'
echo '      deploy compose HOLMES_BASE_URL/HOLMES_API_KEY 映射+app.alert.holmes-model=删(随 M6-07);'

echo '================================================================'
echo '面③ 密钥与 env 键（只列键名不打印值）'
echo '================================================================'
echo "--- .env 中 HOLMES 相关键名:"
grep -oE '^[A-Z_]*HOLMES[A-Z_]*' /opt/build/pr/deploy/.env 2>/dev/null || echo '  (无)'
echo "--- 运行容器 holmesgpt-am1 的 env 键名:"
docker exec holmesgpt-am1 sh -c 'env | cut -d= -f1' 2>/dev/null | sort \
  | while read -r k; do case "$k" in *KEY*|*TOKEN*|*SECRET*|*PASSWORD*|*BEARER*) echo "  $k (敏感,值不回显)";; *) echo "  $k";; esac; done \
  || echo '  (容器不可达)'
echo '裁定: HOLMES_API_KEY(20/.env+compose 映射)=删+回收(轮换 litellm 面所用上游键另行管理,不在本键);'

echo '================================================================'
echo '面④ 监控仪表盘/告警规则 holmes 维度'
echo '================================================================'
grep -rn -i 'holmes' "$REPO/deploy/alert/prometheus" "$REPO/deploy/alertmanager" 2>/dev/null \
  || echo '  (prometheus/alertmanager 规则零 holmes 维度)'
echo '裁定: 零命中=零处置(LeaveAsIs);'

echo '================================================================'
echo '面⑤ 灾备/恢复脚本 holmes 依赖'
echo '================================================================'
grep -rn -i 'holmes' /opt/backups/*.sh 2>/dev/null | grep -v Binary || echo '  (备份/恢复脚本零 holmes 依赖)'
echo '裁定: 零命中=零处置;制品恢复路径按 M6-06 恢复演练记录 RTO/RPO;'

echo '================================================================'
echo '面⑥ 文档锚点（docs 树含 holmes 的文件计数+关键锚）'
echo '================================================================'
grep -ril holmes "$REPO/docs" 2>/dev/null | wc -l | xargs echo 'docs 命中文件数:'
grep -rln '退场\|下线' "$REPO/docs" 2>/dev/null | grep -i 'am6' | head -5 || true
echo '裁定: 历史方案/台账/证据文档=留(豁免:历史事实不改写);决策记录=本扫描后产出;'

echo 'M6_DEPENDENCY_SCAN_OK'
