#!/bin/sh
# ============================================================================
# e2e-am5-09-archive-recovery.sh —— E2E-AM5-09：冷归档/恢复对拍
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.1；落码方案 §M5-19 随件交付）
#
# 必断言（INV-AM5-10 冷归档两条铁律 + V29 manifest 契约）：
#   ① 前置：retention_policy 在案（hot_retention_days 冷却期已过）、legal hold 零生效、
#      目标分区（rca_event_YYYY_MM）行数/内容 digest 基线；
#   ② 归档执行：ArchiveService 导出（COPY 有序）→ 回读 digest 对拍恒等 →
#      manifest EXPORTED→VERIFIED→ARCHIVED 单向；DETACH PARTITION 后分区离开
#      pg_inherits 注册面但表保留（keep_table），父表 rca_event 查询不再含该月行；
#   ③ 恢复对拍：冷层 dump 回灌临时表 → 行数/逐行 digest 与 ① 基线恒等（零丢失）；
#   ④ 失败面不删热数据：冷层不可达 → FAILED_EXPORT 零 manifest 行；备份损坏 →
#      FAILED_VERIFY manifest 留 EXPORTED（审计面）；两态下分区恒挂载；
#   ⑤ legal hold 生效中归档请求 → REJECTED_HOLD 零副作用（INV-AM5-9）；
#      恰一次栅栏：重复归档 → ALREADY_ARCHIVED 零二次导出。
#
# 【骨架】本脚本随 M5-19 交付骨架，部署段（195，L5/G2 门）执行：
#   - 冷层当前裁定 = 本地盘卷（开放项 O-5；file:// 引用），对象存储形态冻结后
#     ③的回灌对拍以该形态等价重演；
#   - IT 面（PostgresArchiveIT 5 案）已锁链路语义；本脚本断言真栈运维动作面。
#
# 用法（195 部署段）：sh e2e-am5-09-archive-recovery.sh
# ============================================================================

set -e

OUT_PREFIX="[E2E-AM5-09]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

log "phase1 基线：过期分区行数/内容 digest 快照（[195]）"
# [195] ① psql：SELECT count(*) FROM rca_event_YYYY_MM + COPY 有序导出 sha256 基线
log "  （骨架期占位：基线快照待 195 部署段激活）"

log "phase2 归档执行：digest 对拍 → manifest 单向 → detach keep_table（[195]）"
# [195] ① 触发 ArchiveService（运维入口）→ manifest state 单向推进核对；
# [195] ② pg_inherits 注册面摘除但表仍在、行原封；父表查询不含该月行
log "  （骨架期占位：归档面待 195 部署段激活）"

log "phase3 恢复对拍：冷层 dump 回灌与基线恒等（[195]）"
# [195] ① COPY FROM STDIN 回灌临时表 → 行数/逐行 digest 与 phase1 基线对拍零丢失
log "  （骨架期占位：恢复面对拍待 195 部署段激活）"

log "phase4 失败面：冷层不可达/备份损坏 → 不删热数据（[195]）"
# [195] ① 停冷层卷（或只读挂载）→ FAILED_EXPORT、manifest 零行、分区恒挂载；
# [195] ② 篡改冷层 dump 单字节 → FAILED_VERIFY、manifest 留 EXPORTED、分区恒挂载
log "  （骨架期占位：失败面对拍待 195 部署段激活）"

log "phase5 hold 面与恰一次栅栏（[195]）"
# [195] ① legal hold 生效中归档 → REJECTED_HOLD 零副作用；解除后可归档；
# [195] ② 重复归档 → ALREADY_ARCHIVED（零二次导出、零 detach）
log "  （骨架期占位：hold/幂等面对拍待 195 部署段激活）"

log "骨架校验完成（phase1~5 真栈断言待 195 部署段激活；链路语义已由 PostgresArchiveIT + ArchiveServiceTest 锁定）"
