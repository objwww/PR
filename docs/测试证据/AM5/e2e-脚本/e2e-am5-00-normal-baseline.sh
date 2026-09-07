#!/bin/sh
# ============================================================================
# e2e-am5-00-normal-baseline.sh —— E2E-AM5-00：正常业务基线（B0）
#                     （LIVE_BUSINESS；AM5 技术方案 §12.2/§12.5；落码方案附录 §三）
#
# 必断言（runall 铁律：本场景失败即停止后续 01~10——禁止用故障场景结果掩盖业务回归）：
#   ① order-arena 正常业务流量窗口：订单成功、业务 SLO 无回归（order-arena 自证面）；
#   ② 无故障注入下控制面零扰动：incident/rca_run/claim/operator_case 增量恒 0；
#   ③ Holmes 主路径健康（LiteLLM/provider 可达基线，供 01/09/10 等场景引用）；
#   ④ 观测链窗口：Prometheus/Alertmanager 对正常业务零误报（AM 走廊零 firing）。
#
# 【骨架】部署段（195，AM4 E2E 释放后）执行；观察窗/对拍 SQL 全 [195] 标注。
# 用法（195 部署段）：sh e2e-am5-00-normal-baseline.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-00]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

SCENARIO_ID="00"
SCENARIO_NAME="normal-baseline"
FIDELITY="LIVE_BUSINESS"

log "phase1 前置：控制面计数基线（[195]）"
# [195] ① 只读 SQL：incident/rca_run/rca_claim/operator_case 当前计数快照（前置事实）
log "  （骨架期占位：基线快照待 195 部署段激活）"

log "phase2 业务流量窗口：order-arena 正常流（[195]）"
# [195] ① 正常流量注入（测试租户）→ 订单成功率/SLO 面读取；
# [195] ② Holmes 主路径健康探针（一次真实 LLM 调用预算内）
log "  （骨架期占位：业务窗口待 195 部署段激活）"

log "phase3 零扰动对拍：控制面增量恒 0（[195]）"
# [195] ① incident/rca_run/claim/operator_case 增量 = 0（正常业务不得产告警管线副作用）；
# [195] ② Prometheus/Alertmanager 零误报（走廊零 firing）
log "  （骨架期占位：零扰动对拍待 195 部署段激活）"

log "scenario-result {\"scenario\":\"$SCENARIO_ID\",\"name\":\"$SCENARIO_NAME\",\"fidelity\":\"$FIDELITY\",\"status\":\"PENDING_DEPLOY_SEGMENT\"}"
log "骨架校验完成（真栈断言待 195 部署段激活；runall 依赖本场景 PASS 才继续 01~10）"
