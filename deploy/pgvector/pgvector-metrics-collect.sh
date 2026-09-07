#!/bin/sh
# ============================================================================
# pgvector-metrics-collect.sh —— M5-21 pgvector 可行性门指标采集（骨架）
#                     （AM5 技术方案 §12.1"E 组"；E2E-AM5-10 pgvector 腿随件）
#
# 红线：本脚本只采集可行性指标，【不启用】pgvector（INV-AM5-9：不过门不启用）。
# 全部 phase 依赖 195 释放后的部署段（本机无 Docker 不计证据，同 IT 纪律）。
#
# 四门对应（deploy/pgvector/pgvector-gate-checklist.md）：
#   phase2 → G1 recall   phase3 → G2 内存   phase4 → G3 延迟   phase5 → G4 备份
# phase1 为环境前置核对（扩展可用但默认不建索引面）。
#
# 用法（195 部署段）：sh pgvector-metrics-collect.sh <PG 连接参数经 env 注入>
# ============================================================================

set -e

OUT_PREFIX="[PGVECTOR-GATE]"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

log "phase1 环境前置：pgvector 扩展可用性核对（只核对不启用，[195]）"
# [195] ① SELECT * FROM pg_available_extensions WHERE name='vector' → 可用性记录；
# [195] ② 断言生产库 shared_preload/extension 面零 vector 残留（门关闭面自证）
log "  （骨架期占位：可用性核对待 195 部署段激活）"

log "phase2 G1 recall：金标查询集 recall@k（[195]）"
# [195] ① 临时 schema 内建实验表 + 金标查询-假设对加载（源自 M5-04 金标场景导出）；
# [195] ② CREATE EXTENSION vector（仅实验 schema）+ HNSW 建索引（记录建索引耗时）；
# [195] ③ recall@k 逐项输出（k=5/10/20）
log "  （骨架期占位：recall 采集待 195 部署段激活）"

log "phase3 G2 内存：索引体积与 RSS 增量（[195]）"
# [195] ① pg_relation_size 索引体积；② 建索引/查询前后 backend RSS 差值采样；
# [195] ③ 对照 2C4G 预算表与 195 内存表输出余量结论
log "  （骨架期占位：内存采集待 195 部署段激活）"

log "phase4 G3 延迟：p50/p95/p99（[195]）"
# [195] ① 单查 profile（冷/热缓存各一轮）；② 并发 profile（8/32 并发各一轮）
log "  （骨架期占位：延迟采集待 195 部署段激活）"

log "phase5 G4 备份：dump/restore 往返对拍（[195]）"
# [195] ① pg_dump 实验表 → restore 到临时库 → 行数+抽样向量距离对拍恒等；
# [195] ② HNSW 索引 restore 后重建（索引不随 dump 迁移的坑）并回归一次查询
log "  （骨架期占位：备份往返待 195 部署段激活）"

log "phase6 清场：实验 schema 整体 drop（门关闭面恢复自证，[195]）"
# [195] ① drop 实验 schema；② 复核生产 schema 零 vector 残留
log "  （骨架期占位：清场待 195 部署段激活）"

log "骨架校验完成（phase1~6 待 195 部署段激活；门保持关闭，四门数据齐全才进启用评审）"
