-- ============================================================================
-- V31 —— BA-52 修复：canary_route_decision.run_id FK 改 DEFERRABLE INITIALLY
--        DEFERRED（决策行/run 行同事务原子对的提交点检查）
--
-- 缺陷事实（195 真栈 E2E-AM6-00 首跑实证，2026-09-08）：
--   生产铸造点（IncidentProjector / RcaRunOrchestrator 的 castRunAndTask）顺序为
--   canaryRouter.route() 内先 append 决策行（引用 runId）、runs.insertRouted() 后落
--   run 行——两者是同一事务的原子对。V25 的 FK 为 NOT DEFERRABLE（即时检查），
--   决策行插入时 run 行尚未在场 → 每次新 run 铸造必 23503 → 投影事务回滚、inbox
--   整组重试 5 次后 DEAD_LETTER。M5-10 的 IT 均先插 run 再落决策（顺序反演），
--   掩盖了生产顺序面；AM5 E2E-05 为骨架从未激活，真栈首跑即踩中。
--   （历史探针告警未爆只因 BA-45 前装配面为 holmesOnly()——NullDecisions 零审计行。）
--
-- 修复语义：约束校验推迟到事务提交点。run+决策两行由同一事务原子写入（两个生产
--   铸造点与 all IT 调用面均如此），提交时 run 行必已在场——检查时点后移不放松
--   不变性：孤儿决策行在提交点依旧被拒（单语句自提交同样在语句末检查）。
--   append-only 面（授权/序列授予）零改动。
-- ============================================================================

alter table canary_route_decision
    alter constraint canary_route_decision_run_id_fkey DEFERRABLE INITIALLY DEFERRED;
