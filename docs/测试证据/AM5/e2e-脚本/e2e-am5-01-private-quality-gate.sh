#!/bin/sh
# ============================================================================
# e2e-am5-01-private-quality-gate.sh —— E2E-AM5-01：私有质量门（B1~B4 真实故障）
#                     （HYBRID_BUSINESS（AM4 Logs/Change replay 组件须列明+fixture digest）；
#                       AM5 技术方案 §12.2；落码方案附录 §三/§四）
#
# 必断言：
#   ① B1~B4 各真实故障 → Holmes/Native 同 EvidenceSnapshot → EvalGateRunner 全链；
#   ② GT 封存后才可读（HOLDOUT_GATE 身份以外恒 0）；family 整组同分区；
#   ③ 采样/配置/引擎/stats 算法指纹齐全（suite-manifest 可对）；
#   ④ 真实候选按数据裁决 REJECT/INCONCLUSIVE/ELIGIBLE 原样留存（禁止改写/丢弃
#      失败 trial/重抽 seed——§四反伪造）；
#   ⑤ 未过门时：pointer/Canary 增量恒 0（REJECT/INCONCLUSIVE 只证明"门正确"）；
#   ⑥ 真实候选与 04 控制候选 candidate_id/eval_run_id/bundle_digest/artifact 目录
#      完全隔离（证据汇总器交叉引用即失败）。
#
# 【骨架】部署段执行；B 场景注入/裁决对拍全 [195]。
# 用法（195 部署段）：sh e2e-am5-01-private-quality-gate.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-01]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

SCENARIO_ID="01"
SCENARIO_NAME="private-quality-gate"
FIDELITY="HYBRID_BUSINESS"

log "phase1 B1~B4 真实故障注入与证据快照（[195]）"
# [195] ① B1 幂等破坏/B2 状态回跳/B3 超时未知/B4 重复告警 各一真实 order-arena 样本；
# [195] ② Holmes/Native 同 Snapshot 冻结（fixture digest 记录；HYBRID 组件列明）
log "  （骨架期占位：故障样本待 195 部署段激活）"

log "phase2 质量门裁决：GT 封存/指纹/五分支（[195]）"
# [195] ① GT 封存后经 HOLDOUT_GATE 读；RAG/AGENT 身份对 HOLDOUT 查询恒 0；
# [195] ② eval_run 指纹面齐全；REJECT/INCONCLUSIVE/ELIGIBLE 原样落 assertions.json
log "  （骨架期占位：裁决面对拍待 195 部署段激活）"

log "phase3 未过门零外溢：pointer/Canary 增量 0（[195]）"
# [195] ① config_bundle active pointer 增量=0；canary 路由增量=0；
# [195] ② 与 04 控制候选目录隔离自证（candidate_id/eval_run_id/digest 无交集）
log "  （骨架期占位：外溢面对拍待 195 部署段激活）"

log "scenario-result {\"scenario\":\"$SCENARIO_ID\",\"name\":\"$SCENARIO_NAME\",\"fidelity\":\"$FIDELITY\",\"status\":\"PENDING_DEPLOY_SEGMENT\"}"
log "骨架校验完成（真栈断言待 195 部署段激活；真实候选裁决原样留存，禁止伪 E2E）"
