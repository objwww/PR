#!/bin/sh
# ============================================================================
# e2e-am5-08-telemetry-outage.sh —— E2E-AM5-08：遥测出口故障不改业务状态
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.1；落码方案 §M5-15④ 随件交付）
#
# 必断言（INV-AM5-8 遥测半边 + 落码方案 §M5-15④ 真证据条款）：
#   ① 基线：正常出口下 control-app 业务链路（webhook→Run 推进→终态）healthy；
#   ② 切断 OTel/Collector 出口（docker stop otelcol-control / 断上游 endpoint）→
#      control-app 业务链路照常：新 Run 照常铸造/推进/收尾，业务表零异常增长；
#   ③ collector 自身：容器 384M + memory_limiter 256M 上限生效，超限只降级/拒收
#      遥测，不影响宿主与其余服务；
#   ④ 恢复出口 → 遥测恢复外发（tail_sampling 错误/慢全保、健康 10%）；
#   ⑤ 全程业务/Run 状态与①基线逐字段对拍零漂移（INV-AM5-8 遥测半边）。
#
# 【骨架】本脚本随 M5-15 交付骨架，部署段（195 + 2C4G，L5/G2 门）执行：
#   - ②的切断动作以 compose 服务级 stop/网络摘除模拟；上游不可达以假 endpoint 注入；
#   - 应用侧 exporter 依赖未上类路径时（M3-29 零代码变更原则），①④的遥测面断言
#     降级为 actuator/prometheus 拉面可见性核对（[195] 标注）。
#
# 用法（195 部署段）：sh e2e-am5-08-telemetry-outage.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-08]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

log "phase1 基线：出口健康下业务链路快照（[195]）"
# [195] ① 注入 B 场景告警 → Run 铸造/推进/终态快照（业务表行级 digest 基线）
log "  （骨架期占位：基线快照待 195 部署段激活）"

log "phase2 切断遥测出口 → 业务零漂移（[195]）"
# [195] ① docker stop otelcol-control（或假 endpoint 注入）→ 重复 phase1 场景；
# [195] ② Run 铸造/推进/终态与基线逐字段对拍零漂移；应用日志无重试风暴刷屏
log "  （骨架期占位：断出口对拍待 195 部署段激活）"

log "phase3 collector 资源上限面（[195]）"
# [195] ① 容器 stats 上限 384M 生效；memory_limiter 拒收只产生 collector 侧告警日志
log "  （骨架期占位：上限面待 195 部署段激活）"

log "phase4 出口恢复 → 遥测恢复（[195]）"
# [195] ① docker start otelcol-control → 健康面 13133 恢复；tail_sampling 采样核对
#     （错误/慢 trace 全在、健康 trace ≈10%）
log "  （骨架期占位：恢复面待 195 部署段激活）"

log "骨架校验完成（phase1~4 真栈断言待 195 部署段激活；配置面已由静态门锁定）"
