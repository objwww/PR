#!/usr/bin/env bash
# mig01-host2-bootstrap.sh — MIG-01：127（117.72.208.68，HOST2 / 2C4G 评测机）基座初始化
#
# 依据：
#   docs/告警系统-演进方案可行性评审与详细改造计划.md  Phase 11 §4 第 1 步、§5 验收门
#   docs/告警系统-2C4G评测机迁移实施方案.md            §三 资源预算 / §四 目录与身份 / §十 M4
#
# 运行方式：root 在目标机本机执行（由 mig01-run.sh 经 SSH 推送调用，也可手工执行）。
# 纪律：
#   - 幂等：已存在的用户/目录/容器/密钥不重建，只核对。
#   - 密钥纪律：WireGuard 私钥只在 /etc/wireguard（0600），证据日志只记公钥与 sha256。
#   - 不触碰 195；不启动 WireGuard（对端公钥需人工交换后才 up，见收尾 NEXT 输出）。
#   - --node-exporter-only：只装 node_exporter + 基线采集（用于 195 侧，BA-54 前置）。
set -euo pipefail

NODE_EXPORTER_IMAGE="${NODE_EXPORTER_IMAGE:-prom/node-exporter:v1.9.1}"  # 执行时核对并改 pin digest
# WireGuard 隧道已存在且活跃（195=10.250.250.1、127=10.250.250.2，/30，用户态 wireguard-go），
# 本脚本只检测记录，不重配、不重启；历史规划值 10.77.0.0/24 已废弃。
HOST1_PUBLIC_IP="${HOST1_PUBLIC_IP:-146.56.195.225}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
EVDIR="/srv/alert-eval/mig01"
EVLOG="${EVDIR}/evidence-${TS}.log"
PASS=0; FAIL=0
NODE_ONLY=0
[ "${1:-}" = "--node-exporter-only" ] && NODE_ONLY=1

mkdir -p "$EVDIR"
exec > >(tee -a "$EVLOG") 2>&1

echo "== MIG-01 bootstrap  ts=${TS} host=$(hostname) role=$([ $NODE_ONLY -eq 1 ] && echo node-exporter-only || echo host2-full)"

check() { # check <名称> <命令...>
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "PASS  $name"; PASS=$((PASS+1));
  else echo "FAIL  $name"; FAIL=$((FAIL+1)); fi
}

# ---------- 1. 基线采集（先留迁移前事实） ----------
echo "--- baseline"
uname -a
nproc
free -m
df -h /
swapon --show || true
docker version --format 'docker-server={{.Server.Version}}'
echo "RTT to 195 (${HOST1_PUBLIC_IP}):"
ping -c10 -W2 "$HOST1_PUBLIC_IP" 2>&1 | tail -2 || echo "ping-unreachable"

# ---------- 2. 专用身份（迁移方案 §四） ----------
if [ $NODE_ONLY -eq 0 ]; then
  echo "--- identities"
  for spec in "evalctl:11000" "eval-runner:11001" "eval-scorer:11002" "eval-importer:11003"; do
    u="${spec%%:*}"; id="${spec##*:}"
    if ! id -u "$u" >/dev/null 2>&1; then
      useradd --system --uid "$id" --shell /sbin/nologin --comment "alert-eval ${u}" "$u"
      echo "created user $u($id)"
    fi
  done
  check "uid:evalctl=11000"    test "$(id -u evalctl)" = "11000"
  check "uid:eval-runner=11001" test "$(id -u eval-runner)" = "11001"
  check "uid:eval-scorer=11002" test "$(id -u eval-scorer)" = "11002"
  check "uid:eval-importer=11003" test "$(id -u eval-importer)" = "11003"

  # ---------- 3. 目录树与属主（迁移方案 §四；answers 仅 scorer 组可读） ----------
  echo "--- directory tree"
  mkdir -p /srv/alert-eval/{manifests,inputs,outputs,answers,scores/{public,private},state,quarantine,archive}
  chown evalctl:evalctl            /srv/alert-eval/{manifests,state,quarantine,archive}
  chown root:eval-runner           /srv/alert-eval/inputs   && chmod 0750 /srv/alert-eval/inputs
  chown eval-runner:eval-scorer    /srv/alert-eval/outputs  && chmod 0750 /srv/alert-eval/outputs
  chown root:eval-scorer           /srv/alert-eval/answers  && chmod 0750 /srv/alert-eval/answers
  chown eval-scorer:eval-importer  /srv/alert-eval/scores/public  && chmod 0750 /srv/alert-eval/scores/public
  chown eval-scorer:eval-scorer    /srv/alert-eval/scores/private && chmod 0700 /srv/alert-eval/scores/private
  check "dir:answers 0750 root:eval-scorer"  test "$(stat -c '%a %U:%G' /srv/alert-eval/answers)" = "750 root:eval-scorer"
  check "dir:scores/private 0700"            test "$(stat -c '%a' /srv/alert-eval/scores/private)" = "700"
  # 负向断言：runner 身份读 answers 必须被拒（§四 权限矩阵）
  check "NEG:eval-runner cannot read answers" bash -c '! sudo -u eval-runner ls /srv/alert-eval/answers >/dev/null 2>&1'

  # ---------- 4. systemd slice 权重（探针优先于评测，§三.3 末段） ----------
  echo "--- systemd slices"
  mkdir -p /etc/systemd/system/probe.slice.d /etc/systemd/system/eval.slice.d
  printf '[Slice]\nCPUWeight=1000\nIOWeight=1000\n' > /etc/systemd/system/probe.slice.d/10-priority.conf
  printf '[Slice]\nCPUWeight=100\nIOWeight=100\n'   > /etc/systemd/system/eval.slice.d/10-priority.conf
  systemctl daemon-reload
  check "slice:probe priority conf" test -f /etc/systemd/system/probe.slice.d/10-priority.conf

  # ---------- 5. WireGuard：检测优先，绝不覆盖已有隧道 ----------
  echo "--- wireguard"
  if wg show wg0 >/dev/null 2>&1; then
    echo "existing tunnel detected, recording live state (no changes):"
    wg show wg0 | grep -E 'public key|peer|endpoint|latest handshake' | sed 's/^/  /'
    ip addr show wg0 | grep -w inet | sed 's/^/  /'
  else
    echo "no wg0 tunnel — scaffold only; 对端公钥人工交换后才可 wg-quick up"
    install -m 700 -d /etc/wireguard
    [ -f /etc/wireguard/wg0.conf ] || echo "# TODO: wireguard config — 参考迁移方案 Phase 11 §3" > /etc/wireguard/wg0.conf
    chmod 600 /etc/wireguard/wg0.conf
  fi
  check "wg: tunnel up or scaffold present" bash -c 'wg show wg0 >/dev/null 2>&1 || test -f /etc/wireguard/wg0.conf'

  # ---------- 6. 日志轮转 ----------
  printf '/srv/alert-eval/**/*.log {\n  weekly\n  rotate 8\n  compress\n  missingok\n  notifempty\n}\n' > /etc/logrotate.d/alert-eval
  check "logrotate conf" test -f /etc/logrotate.d/alert-eval
fi

# ---------- 7. node_exporter（BA-54 前置；两机同装；loopback 绑定，WG 就绪后再放隧道地址） ----------
echo "--- node_exporter"
if ! docker ps --format '{{.Names}}' | grep -qx node-exporter; then
  docker rm -f node-exporter >/dev/null 2>&1 || true
  docker run -d --name node-exporter --restart unless-stopped \
    --memory 64m --cpus 0.1 --pids-limit 64 \
    --read-only --cap-drop ALL --security-opt no-new-privileges:true --tmpfs /tmp \
    -p 127.0.0.1:9100:9100 \
    -v /proc:/host/proc:ro -v /sys:/host/sys:ro -v /:/rootfs:ro \
    "$NODE_EXPORTER_IMAGE" \
    --path.procfs=/host/proc --path.sysfs=/host/sys --path.rootfs=/rootfs
fi
sleep 2
check "node-exporter running"  bash -c 'docker ps --format "{{.Names}}" | grep -qx node-exporter'
check "node-exporter metrics"  curl -sf -o /dev/null http://127.0.0.1:9100/metrics
check "node-exporter limit 64m" bash -c 'test "$(docker inspect -f {{.HostConfig.Memory}} node-exporter)" = "67108864"'
check "MemAvailable metric visible" bash -c 'curl -sf http://127.0.0.1:9100/metrics | grep -q "^node_memory_MemAvailable_bytes"'

# ---------- 8. 收尾：证据 digest + 人工后续步骤 ----------
echo "--- seal"
sha256sum "$EVLOG" | tee "${EVLOG}.sha256" >/dev/null 2>&1 || true
echo "RESULT pass=${PASS} fail=${FAIL} evlog=${EVLOG}"

if [ $NODE_ONLY -eq 0 ]; then
cat <<EOF

NEXT（人工步骤，本脚本不做）：
  1. WireGuard：195↔127 隧道已存在且活跃（195=10.250.250.1、127=10.250.250.2，/30，2026-09-09 实测双向 21ms/0% loss）；
     本脚本只记录不重配。若隧道不存在才需按 Phase 11 §3 新建。
  2. node_exporter：127 已绑隧道地址（10.250.250.2:9100，195 侧 curl 200 实证）；剩余动作 = 195 Prometheus 加 scrape job
     （注意：195 Prometheus 是 loopback 容器，job target 写 10.250.250.2:9100 即可；改 prometheus.yml 需重启/重载，单独确认）。
  3. 负向验收：127→195 隧道地址上 5432/9090/8080 已实测 000 拒绝（正确——服务都绑 loopback）；Gatus 所需 health 入口
     需在 195 增绑隧道地址的窄反向代理（Phase 1 §5），属 Gatus 部署段工作。
  4. 探针（Gatus）按 Phase 1 修正版部署并跑六项验收——此后 195 才处于外部监护下，才允许继续 MIG-03/04。
EOF
fi

[ $FAIL -eq 0 ] || exit 1
