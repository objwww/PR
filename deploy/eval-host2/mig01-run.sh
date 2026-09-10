#!/usr/bin/env bash
# mig01-run.sh — 从本机（Windows/Git Bash 开发机）驱动 MIG-01 基座初始化并回收证据
#
# 用法：
#   bash deploy/eval-host2/mig01-run.sh              # 只初始化 127（HOST2）
#   bash deploy/eval-host2/mig01-run.sh --with-195   # 同时在 195 装 node_exporter（BA-54 前置）
#
# SSH 事实（2026-09-09 实测，勿改错）：
#   127 = 117.72.208.68  密钥 ~/.ssh/hotel_deploy  用户 root
#   195 = 146.56.195.225 密钥 ~/.ssh/id_ed25519   用户 root
#
# 证据回收至 docs/测试证据/HOST2-127/mig01-<UTC 时间戳>/。
set -euo pipefail

HOST2="117.72.208.68"; KEY2="${HOME}/.ssh/hotel_deploy"
HOST1="146.56.195.225"; KEY1="${HOME}/.ssh/id_ed25519"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
EVDIR="docs/测试证据/HOST2-127/mig01-${TS}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WITH_195=0
[ "${1:-}" = "--with-195" ] && WITH_195=1

mkdir -p "$EVDIR"

echo "== MIG-01 on 127 (${HOST2})"
scp -q -i "$KEY2" "${SCRIPT_DIR}/mig01-host2-bootstrap.sh" "root@${HOST2}:/tmp/mig01-host2-bootstrap.sh"
ssh -i "$KEY2" -o BatchMode=yes "root@${HOST2}" 'bash /tmp/mig01-host2-bootstrap.sh' \
  | tee "${EVDIR}/host2-bootstrap-${TS}.log"
# 回收宿主侧证据（含 sha256）
ssh -i "$KEY2" -o BatchMode=yes "root@${HOST2}" \
  'ls -t /srv/alert-eval/mig01/evidence-*.log* | head -2' > "${EVDIR}/.evlist"
while read -r f; do
  scp -q -i "$KEY2" "root@${HOST2}:${f}" "${EVDIR}/"
done < "${EVDIR}/.evlist"
rm -f "${EVDIR}/.evlist"

if [ $WITH_195 -eq 1 ]; then
  echo "== node_exporter on 195 (${HOST1})"
  scp -q -i "$KEY1" "${SCRIPT_DIR}/mig01-host2-bootstrap.sh" "root@${HOST1}:/tmp/mig01-node-exp.sh"
  ssh -i "$KEY1" -o BatchMode=yes "root@${HOST1}" \
    'bash /tmp/mig01-node-exp.sh --node-exporter-only' \
    | tee "${EVDIR}/host1-node-exporter-${TS}.log"
fi

echo "== done, evidence at ${EVDIR}"
grep -h '^RESULT\|^FAIL' "${EVDIR}"/*.log || true
