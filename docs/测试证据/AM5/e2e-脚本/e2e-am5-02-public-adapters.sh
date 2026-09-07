#!/bin/sh
# ============================================================================
# e2e-am5-02-public-adapters.sh —— E2E-AM5-02：公共 benchmark Adapter
#                     （PUBLIC_REPLAY；AM5 技术方案 §12.2/§12.3；落码方案附录 §三）
#
# 必断言：
#   ① RCA-100 v1.1 + manifest digest 固定（禁 latest）；来源九字段齐全；
#   ② artifact/digest 落档；PUBLIC_BENCHMARK 身份与私有 HOLDOUT 物理隔离
#      （私有 HOLDOUT 查询恒 0；公共结果禁入私有主质量门、禁驱动 ConfigBundle 激活）；
#   ③ answer key 未获授权 → fail-closed NOT_AVAILABLE_AUTH（唯一允许 BLOCKED_EXTERNAL
#      的分支，且必须有授权核查记录；不得伪造答案或以 RCAEval 顶名）；
#   ④ RCAEval 辅助回归 + Adapter fail-closed 测试仍必须执行（阻塞分支不豁免）。
#
# 【骨架】部署段执行；RCA-100 授权核查记录 [195]。
# 用法（195 部署段）：sh e2e-am5-02-public-adapters.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-02]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

SCENARIO_ID="02"
SCENARIO_NAME="public-adapters"
FIDELITY="PUBLIC_REPLAY"

log "phase1 RCA-100 授权核查（唯一 BLOCKED_EXTERNAL 分支，[195]）"
# [195] ① Redistribution 授权核查记录落档（联系方式/日期/结论）；
# [195] ② 未授权 → NOT_AVAILABLE_AUTH fail-closed 记录，仍继续 phase3 辅助回归
log "  （骨架期占位：授权核查待 195 部署段激活）"

log "phase2 manifest 固定与来源九字段（[195]）"
# [195] ① RCA-100 v1.1 manifest digest 对拍（禁 latest）；来源九字段逐项核
log "  （骨架期占位：manifest 面待 195 部署段激活）"

log "phase3 Adapter fail-closed + RCAEval 辅助回归（[195]）"
# [195] ① Adapter 缺 manifest/digest 错 → 显式拒绝（不伪造）；
# [195] ② RCAEval RE1/2/3 固定 manifest 辅助回归执行；
# [195] ③ 私有 HOLDOUT 查询恒 0；公共结果未触碰 config_bundle/canary 面
log "  （骨架期占位：辅助回归待 195 部署段激活）"

log "scenario-result {\"scenario\":\"$SCENARIO_ID\",\"name\":\"$SCENARIO_NAME\",\"fidelity\":\"$FIDELITY\",\"status\":\"PENDING_DEPLOY_SEGMENT\"}"
log "骨架校验完成（真栈断言待 195 部署段激活；未授权分支只说明外部一致性未完成，不得生成通过结论）"
