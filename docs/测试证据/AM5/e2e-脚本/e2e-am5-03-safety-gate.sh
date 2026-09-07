#!/bin/sh
# ============================================================================
# e2e-am5-03-safety-gate.sh —— E2E-AM5-03：安全门五面红队（PUBLIC_REPLAY/安全）
#                     （AM5 技术方案 §12.3；落码方案 §M5-07④ 随件交付）
#
# 必断言（§12.3 E2E-AM5-03 行）：prompt injection、跨租户、越权工具、写意图、
# 非法 schema 五面红队输入穿过真实门禁——任一安全违规即 REJECT（INV-AM5-4
# fail-closed）；零 ConfigBundle 激活、零 Canary、零外部写副作用；拒绝理由与
# 原始计数可回溯。
#
# 【骨架】本脚本随 M5-07 交付骨架，部署段（195 + 2C4G，L5/G2 门）执行：
#   - 红队输入装载面：eval_private REDTEAM 分区（M5-01/02 已落的版本化数据面）；
#   - 门禁执行面：control-app 内嵌 SafetyGate（M5-07）经 M5-08 EvalGateRunner
#     接线后的真实调用（本骨架期 = UT 面已锁，真栈面待 195）；
#   - 记录面 SQL 断言点标注 [M5-08]，门禁记录表落地后补全表名/列名。
#
# 用法（195 部署段）：sh e2e-am5-03-safety-gate.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-03]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

# ---------------------------------------------------------------------------
# phase0 前置静止面：零 ConfigBundle 激活、零 Canary 放量（副作用基线）
# ---------------------------------------------------------------------------
log "phase0 副作用基线（零激活/零放量）"
# CONFIG_ACTIVE=$(curl -fsS http://control-app:8080/... )  # [M5-08] 门禁记录面落地后接真查询
# [ "$CONFIG_ACTIVE" = "0" ] || fail "基线即有 ConfigBundle 激活"
log "  （骨架期占位：ConfigBundle/Canary 零值基线断言待 M5-08 记录面 + 195 部署段补全）"

# ---------------------------------------------------------------------------
# phase1~5 五面红队输入逐面过门（每面：装载 → 过真实门禁 → 断 REJECT → 回溯）
# 五面 = SCHEMA / INJECTION / CROSS_TENANT / UNAUTHORIZED_TOOL / WRITE_INTENT
# （eval/domain/model/SafetyFace 分类码；拒绝记录消费 AM4 ToolPolicy/ToolGateway
#   拦截面 + investigation 拒绝记录，门不重判——落码方案 §M5-07①）
# ---------------------------------------------------------------------------
for FACE in SCHEMA INJECTION CROSS_TENANT UNAUTHORIZED_TOOL WRITE_INTENT; do
    log "phase $FACE：红队样本过真实门禁"
    # ① 装载：从 eval_private REDTEAM 分区取该面样本（GT 隔离纪律：评分身份读）
    #    docker exec arena-e2e-cli python3 /e2e/redteam_load.py "$FACE"   # [M5-08]
    # ② 过门：经 M5-08 EvalGateRunner 真实调用 SafetyGate.check
    #    VERDICT=$(... )                                                   # [M5-08]
    # ③ 断言：任一违规即 REJECT，不得 PASS
    #    [ "$VERDICT" = "REJECT" ] || fail "$FACE 面红队样本未被拒绝（fail-closed 破面）"
    # ④ 回溯：拒绝理由与原始计数可查（机器码英文 + 引用位）
    #    REASONS=$(e5_sql "select ... from <m508_gate_record> where face='$FACE'")  # [M5-08]
    #    [ -n "$REASONS" ] || fail "$FACE 面拒绝理由不可回溯"
    log "  （骨架期占位：$FACE 断言序列待 M5-08 记录面表名落定后激活）"
done

# ---------------------------------------------------------------------------
# phase6 副作用终检：零 ConfigBundle 激活、零 Canary、零外部写副作用
# ---------------------------------------------------------------------------
log "phase6 副作用终检"
# 外部写副作用面：靶场订单/库存/支付状态与注入前一致（B0 基线对拍）
# docker exec arena-e2e-cli python3 /e2e/driver.py baseline-diff --expect-zero   # [195]
log "  （骨架期占位：外部写零副作用对拍待 195 部署段激活）"

log "骨架校验完成（真栈断言序列待 M5-08 记录面 + 195 部署段）"
