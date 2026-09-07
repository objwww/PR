#!/bin/sh
# ============================================================================
# e2e-am5-10-history-search.sh —— E2E-AM5-10：历史检索门（FTS 两态 + pgvector 采集）
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.2；落码方案附录 §三）
#
# 必断言（INV-AM5-9 历史检索半边）：
#   ① FTS off（默认）：历史注入恒 0（后端零触达由 UT 锁定，本场景断言真栈 off 面）；
#   ② FTS on（实验开门态）：命中仅产 UNTRUSTED_HYPOTHESIS；历史命中不得直接发布根因；
#   ③ 跨租户/HOLDOUT 泄漏恒 0（跨租户查询返回 0；RAG/AGENT 身份 HOLDOUT 查询拒绝）；
#   ④ pgvector 门保持关闭：不建索引/不启用扩展面；仅跑 pgvector-metrics-collect.sh
#      采集可行性指标（不过门不启用）。
#
# 【骨架】部署段执行；FTS on 态需实验 flag 开启的隔离实例（生产实例恒 off）。
# 用法（195 部署段）：sh e2e-am5-10-history-search.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-10]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

SCENARIO_ID="10"
SCENARIO_NAME="history-search"
FIDELITY="CONTROL_FIXTURE"

log "phase1 off 态（默认）：零历史注入（[195]）"
# [195] ① B1/B2 历史事实在库 → 检索入口 off 态调用 → 返回空且历史面零触达
log "  （骨架期占位：off 面待 195 部署段激活）"

log "phase2 on 态（隔离实验实例）：仅产 UNTRUSTED_HYPOTHESIS（[195]）"
# [195] ① 实验实例开启 app.eval.hypothesis-search.enabled → 命中逐条 trustLevel 对拍；
# [195] ② 报告发布面：仅凭历史命中的候选不得发布（发布门拒绝路径留证）
log "  （骨架期占位：on 面待 195 部署段激活）"

log "phase3 泄漏面：跨租户/HOLDOUT 恒 0（[195]）"
# [195] ① 跨租户查询 → 0 行；RAG/AGENT 身份 HOLDOUT 查询 → 显式拒绝
log "  （骨架期占位：泄漏面对拍待 195 部署段激活）"

log "phase4 pgvector 门关闭面 + 可行性采集（[195]）"
# [195] ① 生产/实验实例 pg_extension 零 vector（门关闭自证）；
# [195] ② deploy/pgvector/pgvector-metrics-collect.sh 六 phase 执行 → 指标入 AA-26 证据包
log "  （骨架期占位：pgvector 采集待 195 部署段激活）"

log "scenario-result {\"scenario\":\"$SCENARIO_ID\",\"name\":\"$SCENARIO_NAME\",\"fidelity\":\"$FIDELITY\",\"status\":\"PENDING_DEPLOY_SEGMENT\"}"
log "骨架校验完成（真栈断言待 195 部署段激活；检索语义已由 HypothesisSearchServiceTest 锁定）"
