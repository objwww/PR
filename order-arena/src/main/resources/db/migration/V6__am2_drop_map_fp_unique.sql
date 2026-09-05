-- ============================================================================
-- V6 —— 移除 uq_scenario_map_fp（V5 过严约束修正）
--
-- 缘由：指纹 = C-6 对「告警身份」（alertname/service/job/instance/severity/
-- fault_type）的函数，与 scenario 并非一对一——同型故障的合法多场景
-- （主场景 f1-e2e 与 TTL 演练 f1-ttl）指纹相同，而激活固定写 mapping_version=1，
-- uq(fingerprint, mapping_version) 使第二个场景必然 409，冻结的
-- M2-28「TTL 清理演练」流程无法执行。
--
-- 指纹归属裁决面 = Live E2E slot=1 串行纪律（一次仅一个场景在野）+ 会话状态；
-- 版本链语义不变：uq_scenario_map_version(scenario_id, mapping_version) 保留。
-- AM3 M3-10 导出消费时按 (fingerprint, 最近会话状态) 取行，本迁移不改变字段面。
-- ============================================================================

drop index if exists arena.uq_scenario_map_fp;
