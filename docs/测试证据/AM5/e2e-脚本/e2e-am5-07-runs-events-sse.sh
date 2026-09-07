#!/bin/sh
# ============================================================================
# e2e-am5-07-runs-events-sse.sh —— E2E-AM5-07：runs 投影 / rca_event 游标 / SSE 流
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.1；落码方案 §M5-13④ 随件交付）
#
# 必断言（§12.1 E2E-AM5-07 行 + 落码方案 §M5-13④ 拆解验收）：
#   ① runs 队列投影：GET /api/rca-runs 的 bucket/stage/progress 与 rca_run+rca_task
#     状态对拍（DB 是真相源）；detail 的 engine/config = V25 路由四列快照；
#   ② events 初读/续读：?after_seq=0 初读全量 → 注入新事件 → 游标续读只回增量，
#     latestSeq 对拍 rca_run.last_event_seq；
#   ③ SSE 流：POST stream-ticket 换票（TTL 30s 单次绑 run+主体）→ GET stream 开流
#     → 每条消息 id:seq → 断开重连带 Last-Event-ID 只续传增量；
#   ④ 游标过期/resync：人为制造 seq 缺口（或以超窗 after_seq 开流）→ resync 事件、
#     停止增量（Unleash delta 先例）；
#   ⑤ 权限面：无/错 bearer 401；同票二用、错主体、过期票开流均 401（对探测者零信息）；
#   ⑥ 慢客户端（INV-AM5-8）：客户端挂住不断开 → Run 照常推进到终态（SSE 单次排水
#     + complete，服务端零长驻线程——结构性不可能拖慢）。
#
# 【骨架】本脚本随 M5-13 交付 SSE 半骨架（命令面 phases 归 M5-14 随件补齐），
# 部署段（195 + 2C4G，L5/G2 门）执行：
#   - 缺口注入需真栈 rca_event 手工行操作（append-only 由 appender 保证，
#     缺口只能 DBA 面/归档模拟制造——[195] 标注）；
#   - IT 缺席本地面（Docker 不可用），真证据待 195 释放后统一补（IT 跳过不计证据）。
#
# 用法（195 部署段）：sh e2e-am5-07-runs-events-sse.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-07]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

BASE="${BASE:-http://localhost:8080}"
BEARER="${APP_OPERATOR_API_BEARER:?需 APP_OPERATOR_API_BEARER（O-4 过渡鉴权）}"

log "phase1 runs 队列/详情投影对拍（[195]）"
# [195] ① GET /api/rca-runs → rows[].bucket/stage/progress 与 rca_run/rca_task 逐行对拍；
# [195] ② GET /api/rca-runs/{id} → run.engine/config 对拍 V25 路由四列（Run 启动固定）；
# [195] ③ severity/owner 为 null（C-18①：无列如实 null，不冒充）
log "  （骨架期占位：投影对拍待 195 部署段激活）"

log "phase2 events 初读 + 游标续读（[195]）"
# [195] ① ?after_seq=0 初读全量 → latestSeq == rca_run.last_event_seq；
# [195] ② 注入新事件（调查推进）→ 以原游标续读只回增量行、gap=false
log "  （骨架期占位：游标面待 195 部署段激活）"

log "phase3 SSE 换票/开流/断线重连（[195]）"
# [195] ① POST events/stream-ticket → ticket；同 ticket 第二次开流 → 401（单次）；
# [195] ② GET events/stream?ticket= → 事件流每条 id:seq；
# [195] ③ 断开后以 Last-Event-ID 重连（新票）→ 只收增量；
# [195] ④ 错 subject / 过期票（>30s）开流 → 401
log "  （骨架期占位：SSE 面待 195 部署段激活）"

log "phase4 游标过期 → resync（[195]）"
# [195] ① 以 after_seq > last_event_seq 开流/查询 → gap=true / resync 事件、零增量行；
# [195] ② 归档模拟制造 seq 缺口 → 增量停止 + resync(latestSeq)
log "  （骨架期占位：resync 面待 195 部署段激活）"

log "phase5 慢客户端不拖慢 Run（INV-AM5-8）（[195]）"
# [195] ① 客户端开流后挂住不读 → Run 照常推进至终态（时延对拍无流基线）
log "  （骨架期占位：背压面待 195 部署段激活）"

log "骨架校验完成（phase1~5 真栈断言待 195 部署段激活；游标/gap/ticket 语义已由 UT 锁定）"
