#!/bin/sh
# ============================================================================
# e2e-am5-06-operator-case.sh —— E2E-AM5-06：OperatorCase 幂等合并/并发认领/SLA 升级
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.3；落码方案 §M5-11④ 随件交付）
#
# 必断言（§12.3 E2E-AM5-06 行 + 落码方案 §M5-11④ 拆解验收）：
#   ① 来源面：B2/B3/B4 产生 UNRESOLVED/NEEDS_REVIEW/预算耗尽 → OperatorCase 开单
#     （reason_code 机器码英文：CLAIM_CONFLICT/BUDGET_NEAR_LIMIT/REPORT_REVIEW 等）；
#   ② 合并幂等：同 tenant+fingerprint 连续三次发生 → 只合并一单（revision+1 +
#     activity 追加，不新建行）；
#   ③ 双人并发认领：同 expectedRevision 双 claim 恰一人成功（败者冲突零副作用）；
#   ④ 非法状态迁移拒绝（RESOLVED 吸收态：再 claim/resolve/escalate 均拒绝）；
#   ⑤ ack/resolve：resolve 必填结构化 reason(code,note)；结案不回写篡改 Run 结论
#     （DB 对拍 run 行结案前后逐字段一致）；
#   ⑥ SLA 升级恰一次：同 idempotency-key 重放 → 审计行/revision 只 bump 一次；
#     notify_outbox.case_id 关联列为 AM7 IN_APP 渠道预留缝（本任务只落列）。
#
# 【骨架】本脚本随 M5-11 交付骨架，部署段（195 + 2C4G，L5/G2 门）执行：
#   - M5-11 无 HTTP 面（命令 API 归 M5-12 OperatorApiController）——phase3~5 的
#     命令驱动在 M5-12 落地后升级为 API 真调，此前仅 DB/服务面可断言；
#   - 开单来源（Reconciler/预算耗尽信号）接真栈需 AM4 注入链路（[195] 标注）。
#
# 用法（195 部署段）：sh e2e-am5-06-operator-case.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-06]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

# ---------------------------------------------------------------------------
# phase1 来源面开单（[195]：B2/B3/B4 合成信号 → Case）
# ---------------------------------------------------------------------------
log "phase1 合成 B2/B3/B4 信号驱动开单（UNRESOLVED/NEEDS_REVIEW/预算耗尽）"
# [195] ① 注入裁决冲突（双候选均有证据）→ reason_code=CLAIM_CONFLICT
# [195] ② 注入预算近限 → reason_code=BUDGET_NEAR_LIMIT；③ 注入报告复核 → REPORT_REVIEW
# [195] ④ DB 断言 operator_case 三行齐备、evidence_refs N≥1、快照列（snapshot_digest/
#     observed_generation）= 创建时值
log "  （骨架期占位：来源链路待 195 部署段激活）"

# ---------------------------------------------------------------------------
# phase2 合并幂等（[195]：同 fingerprint 连续三次）
# ---------------------------------------------------------------------------
log "phase2 同 tenant+fingerprint 三次发生只合并一单（[195]）"
# [195] ① 同 fingerprint 重复注入三次 → operator_case 单行 revision=3、activities=3；
# [195] ② 换 fingerprint 再注入 → 新行（合并不吞新故障）
log "  （骨架期占位：合并断言待 195 部署段激活）"

# ---------------------------------------------------------------------------
# phase3 双人并发认领 + 命令面（[195]；M5-12 API 落地后升级 API 真调）
# ---------------------------------------------------------------------------
log "phase3 并发认领恰一人成功 + ack/resolve 契约（[195]）"
# [195] ① 双 operator 同 expectedRevision claim → 恰一人成功（revision=2, owner=胜者,
#     status=ACKED），败者冲突响应零状态改写；
# [195] ② resolve 缺 reason → 拒绝；带结构化 {code,note} → RESOLVED + resolution 落行；
# [195] ③ RESOLVED 后再 claim/resolve/escalate → 非法迁移拒绝；
# [195] ④ 结案前后 DB 对拍 rca_run/rca_report 行逐字段一致（结案不回写篡改 Run）
log "  （骨架期占位：命令面断言待 M5-12 API + 195 部署段激活）"

# ---------------------------------------------------------------------------
# phase4 SLA 升级恰一次（[195]）
# ---------------------------------------------------------------------------
log "phase4 SLA 升级恰一次 + outbox 关联面（[195]）"
# [195] ① resolve_due 逾期触发升级 → audits 追加 SLA_ESCALATED、revision+1、状态不变；
# [195] ② 同 idempotency-key 重放 → 只 bump 一次；③ notify_outbox.case_id 列在位
#     （写入面 = AM7 IN_APP 渠道配套）
log "  （骨架期占位：升级断言待 195 部署段激活）"

log "骨架校验完成（phase1~4 真栈断言待 195 部署段 + M5-12 API 激活；合并/CAS/恰一次语义已由 UT+IT 锁定）"
