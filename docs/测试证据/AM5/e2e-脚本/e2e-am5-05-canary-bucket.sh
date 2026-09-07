#!/bin/sh
# ============================================================================
# e2e-am5-05-canary-bucket.sh —— E2E-AM5-05：Canary 稳定分桶黏性与比例审计面
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.3；落码方案 §M5-10④ 随件交付）
#
# 必断言（§12.3 E2E-AM5-05 行 + 落码方案 §M5-10④ 拆解验收）：
#   ① 黏性：同一 groupId:id 跨多次 Run 铸造 bucket 恒定（murmur3_x86_32(seed=0)
#     规范化键 + 无模偏公式 (hash_u32*100)>>>32，千次不变量）；
#   ② 值域：canary_route_decision.bucket 全量 ∈ [0,100)；
#   ③ 决策值域：decision ∈ 8 值枚举（NO_ACTIVE_BUNDLE/CANARY_DISABLED/
#     NO_STICKINESS_KEY/WHITELISTED/BUCKETED_NATIVE/BUCKETED_HOLMES/
#     NATIVE_DEFERRED/BLAST_RADIUS_STOPPED）；
#   ④ 白名单直达：whitelist 命中 id → WHITELISTED + engine=NATIVE；
#   ⑤ percent=0 → BUCKETED_HOLMES 且桶位照记（0 不是 CANARY_DISABLED）；
#   ⑥ 独立实现对拍：python3 纯实现 murmur3 重算 DB 中 stickiness_key 的桶位，
#     与 canary_bucket 逐行一致（交叉验证，非同源复读）；
#   ⑦ 爆炸半径：max_native_runs 到顶后 NATIVE 意愿 → BLAST_RADIUS_STOPPED
#     （审计表无状态重查即自愈）。
#
# 【边界】本脚本禁止接生产告警流量：断言面全部由合成注入驱动。生产 1% 灰度
#   属 M6-01（方案 §12.3 原文），不在 M5-10 交付面内。
#
# 【骨架】本脚本随 M5-10 交付骨架，部署段（195 + 2C4G，L5/G2 门）执行：
#   - phase1 需合成告警注入 → Run 铸造真栈（[195] 标注）；
#   - phase2/3 需 DB 直查面（docker exec postgres psql）；
#   - 路由判定无独立 HTTP 面（Run 铸造点进程内判定），无法离线真调。
#
# 用法（195 部署段）：sh e2e-am5-05-canary-bucket.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-05]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

# ---------------------------------------------------------------------------
# phase1 合成注入驱动 Run 铸造（[195]：真栈窗口；禁生产流量）
# ---------------------------------------------------------------------------
log "phase1 合成注入 N 组 (groupId,id) 驱动路由与审计落行"
# [195] ① 发布 bundle 并激活，content.canary = {"percent":20,"whitelist":["whitelist-id"],"max_native_runs":3}
# [195] ② 合成注入 ≥30 组不同 id 的告警（whitelist-id 混入），经告警→投影→Run 铸造链路
# [195] ③ 记录注入集合为 phase2/3 的对拍基准
log "  （骨架期占位：合成注入待 195 部署段激活）"

# ---------------------------------------------------------------------------
# phase2 审计面 DB 直查（[195]：黏性/值域/白名单/percent=0）
# ---------------------------------------------------------------------------
log "phase2 canary_route_decision 审计面对拍（[195] DB 面）"
# [195] docker exec postgres psql -U control_app -c "
#   select stickiness_key, count(distinct canary_bucket) as buckets,
#          min(canary_bucket), max(canary_bucket), array_agg(distinct decision)
#   from canary_route_decision group by stickiness_key"
# [195] ① buckets=1 每键（黏性）；② min>=0 且 max<100（值域）；
# [195] ③ 决策值域 ⊆ 8 值枚举；④ whitelist-id 行 WHITELISTED + rca_run.engine='NATIVE'；
# [195] ⑤ percent=0 子集行 decision='BUCKETED_HOLMES' 且 canary_bucket 非空
log "  （骨架期占位：审计对拍待 195 部署段激活）"

# ---------------------------------------------------------------------------
# phase3 独立实现桶位对拍（[195]：python3 纯实现 murmur3_x86_32）
# ---------------------------------------------------------------------------
log "phase3 murmur3 独立实现对拍（[195]）"
# [195] python3 内联纯实现（C1=0xcc9e2d51 C2=0x1b873593 seed=0 + fmix），
#   规范化 trim + Locale.ROOT 小写，键 = group + ":" + id，
#   桶 = (hash_u32 * 100) >>> 32；参考向量 hash("")=0, hash("hello")=613153351
#   先自证再对 DB 每行 stickiness_key 重算 == canary_bucket（非同源复读）
log "  （骨架期占位：独立实现对拍待 195 部署段激活）"

# ---------------------------------------------------------------------------
# phase4 爆炸半径停放量（[195]：max_native_runs 到顶）
# ---------------------------------------------------------------------------
log "phase4 爆炸半径上限停放量（[195]）"
# [195] max_native_runs=3 → 第 4 个 NATIVE 意愿 id 注入 → 断言其审计行
#   decision='BLAST_RADIUS_STOPPED' 且 rca_run.engine='HOLMES'（降级不丢路由事实）
log "  （骨架期占位：停放量断言待 195 部署段激活）"

log "骨架校验完成（phase1~4 真栈断言待 195 部署段激活；黏性/值域/对拍逻辑已由 UT+IT 锁定）"
