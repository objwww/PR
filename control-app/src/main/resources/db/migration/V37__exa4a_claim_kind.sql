-- ============================================================================
-- V37 —— EX-A4a F06 Claim 四类型存储契约（docs/告警-EXA4a-必要正确性.md §5）
--
--   ClaimKind 四值（SYMPTOM/HYPOTHESIS/ROOT_CAUSE/EXCLUSION，语义源 = 全系统
--   收口主计划 §8.4，冻结）：kind 是准入元数据非内容身份——不进 claim_fingerprint/
--   claim_hash 双哈希（M4-21/22 契约维持，回放比对稳定）。
--   可空：存量行 null = 未定型（读面展开时归一 HYPOTHESIS；行读面待消费者出现
--   再建——BA-41 同律）。
--   类型准入与转换逻辑（Observation→Claim 转换、类型语义门、Holmes→typed Claim）
--   归 R7c 单一责任人（评审 P1-01 分工）——本迁移只交存储面，零转换逻辑。
--   回滚：drop 约束一列（先滚应用后滚库——store 写列，应用先行回退即无写面）。
--   编号纪律：rebase 时以下一可用号为准替换 V37。
-- ============================================================================

alter table rca_claim add column kind varchar(16);

alter table rca_claim
    add constraint ck_rca_claim_kind
    check (kind in ('SYMPTOM', 'HYPOTHESIS', 'ROOT_CAUSE', 'EXCLUSION'));

comment on column rca_claim.kind is
    'EX-A4a F06 断言四类型（主计划 §8.4 语义冻结；缺省 HYPOTHESIS=保守形态；准入/转换归 R7c）';
