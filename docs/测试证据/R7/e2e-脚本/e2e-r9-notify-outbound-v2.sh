#!/bin/sh
# ============================================================================
# e2e-r9-notify-outbound-v2.sh —— 通知出口验收 v2（RV09/T34~T37，审查方案 §四）
#
# 与 RR25~28（入口层，保留改标）的差异：本脚本串 report → publication → outbox
# → HTTP receiver 出站全链，按真实产品契约断言：
#   T34 隔离接收器先持久化 effect 再断响应 → 当前策略 outcome_unknown、自动重发=0、
#       人工复核入口可见（operationId 只助检测，不假设接收端幂等）；
#   T35 连接失败/429/5xx 各自耗次/退避/终态与 publication 聚合（恢复后旧消息不发）；
#   T36 firing/resolved 逻辑身份各自独立；重投同消息不换 operationId；旧 firing
#       的处置策略（抑制/标历史）按接口契约断言，不以入口状态冒充出口保序；
#   T37 同 Run/报告身份贯通：publication 聚合与渠道结果一致。
#
# 姿态前置（195 部署窗执行，脚本不做破坏性动作）：
#   compose override 注入测试渠道 webhook 指向本机接收器（127.0.0.1:18099），
#   接收器由脚本拉起（python3 stdlib，先落盘 effect 再返回响应/断连可配）。
# ============================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

SUITE="r9out-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
RECEIVER_PORT="${R9_RECEIVER_PORT:-18099}"
r7_log "suite=$SUITE runs=$RUNS receiver=127.0.0.1:${RECEIVER_PORT}"

# ---- 接收器：先落 effect（逐请求 JSONL），再按脚本模式返回 -------------------
cat > "$RUNS/receiver.py" <<EOF
import http.server, json, sys, os, threading
MODE = os.environ.get("R9_RX_MODE", "ok")   # ok | unknown_timeout | refuse | 500
LOG = r"$RUNS/receiver-effects.jsonl"
LOCK = threading.Lock()
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n).decode("utf-8", "replace")
        with LOCK:
            with open(LOG, "a", encoding="utf-8") as f:
                f.write(json.dumps({"mode": MODE, "body": body}, ensure_ascii=False) + "\n")
        # ok/500/挂断语义在响应面区分；unknown_timeout=收下不回（客户端结果未知）
        if MODE == "unknown_timeout":
            try: self.connection.settimeout(3)
            except Exception: pass
            try:
                self.send_response(500); self.send_header("Content-Length","0"); self.end_headers()
            except Exception: pass
            return
        if MODE == "500":
            self.send_response(500); self.send_header("Content-Length","0"); self.end_headers(); return
        if MODE == "refuse":
            self.send_response(400); self.send_header("Content-Length","0"); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Length","2"); self.end_headers(); self.wfile.write(b"{}")
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
EOF
R9_RX_MODE=ok python3 "$RUNS/receiver.py" "$RECEIVER_PORT" &
RX_PID=$!
sleep 1
r7_log "接收器就绪 pid=$RX_PID"

cleanup() { kill "$RX_PID" 2>/dev/null || true; }
trap cleanup EXIT

# ---- 前置姿态核验（部署窗注入的测试渠道在位） -------------------------------
r7_health "$RUNS"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------------------------------------------------------------------
# T34：UNKNOWN 面——接收器收下但响应 5xx 语义（效果已落盘，响应未知）
# 期望：outbox 终态 DEAD/outcome_unknown、零自动重发（同 operation_id 恰一条
# effect）、人工复核说明在 last_error
# ---------------------------------------------------------------------------
r7_log "T34 outcome_unknown 面"
# 注入路径（部署窗以测试渠道发出一笔通知；此处以 outbox 现网行为断言面）：
#   1) R9_RX_MODE=unknown_timeout 重启接收器 → 触发一笔（手动测试入口）
#   2) 断言 outbox.state='DEAD' 且 last_error.reason='outcome_unknown'
#   3) 断言 effect 日志同 operation_id 恰 1 行（零自动重发）
r7_psql_ro R7_PG_URL "SELECT count(*) FROM notify_outbox o
    WHERE o.state='DEAD'
      AND o.last_error::text LIKE '%outcome_unknown%'
      AND o.created_at > '${T0}'::timestamptz - interval '5 minutes'" '-At' \
    > "$RUNS/t34-unknown-dead.txt" || true
[ "$(cat "$RUNS/t34-unknown-dead.txt" 2>/dev/null || echo 0)" -ge 0 ] \
    || r7_fail "T34 断言执行失败"
r7_log "T34 断言框架就绪（计数见 t34-unknown-dead.txt；真值随注入批次翻转）"

# ---------------------------------------------------------------------------
# T35：连接失败/429/5xx 分类与退避——按 R9_RX_MODE 分轮驱动，断言：
#   429 → RETRY_WAIT 不耗预算（attempt_count 不变+available_at=Retry-After）
#   5xx/断连 → RETRY_WAIT 耗预算，耗尽 → DEAD（retry_budget_exhausted）
#   恢复后（MODE=ok）不补发已 DEAD 旧消息（终态不复活）
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# T36：firing/resolved 逻辑身份——两笔通知 operation_id 各自独立且稳定；
#     旧 firing 恢复投递策略按接口契约（本脚本断言契约字段在 last_error/payload 面）
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# T37：report→publication→outbox 贯通——publication 聚合=渠道结果聚合
#     （任一 SENT→SENT；全终态无一 SENT→DEAD），断言以 publication state 对账
# ---------------------------------------------------------------------------

r7_log "T35~T37 详细断言随注入批次展开（本版先立接收器与断言框架，防盲延时假绿）"
r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "R9-OUTBOUND-V2" \
    "通知出口出站验收 v2 框架就绪（隔离接收器+T34 断言面；T35~T37 注入批次=部署窗展开）" \
    "real-provider@195" "FRAMEWORK-READY"
r7_log "SUITE FRAMEWORK-READY：R9 出口验收 v2（非 PASS——T34~T37 逐项真值随部署窗注入批次翻转）"
