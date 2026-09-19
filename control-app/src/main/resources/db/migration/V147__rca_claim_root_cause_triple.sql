-- ============================================================================
-- V147 —— 断言投影结构化根因三元组存储面（告警-Agent 根因评分贯通修复）
--
--   背景：NativeReportAdapter 旧实现把确认 claim 的 scope（恒 "primary"）与
--   claimKey（"c1" 等）塞进 EvidencePackage v2 root_cause{component,fault_type}，
--   评分器（SynonymLexicon 全串精确等值）因此结构性恒 miss——Agent 判对也零分。
--   本迁移交存储面：主 Agent FINAL 提案的 root_cause 三元组（协议解析即校验，
--   长度上限对齐 TypedRootCause 128/64/128）经检查点 → 投影落本三列，报告相位
--   直填评分输入。
--   可空：存量行与"模型未提供三元组"的新行均为 null = 诚实降级（报告面落
--   unknown/unresolved/NO_CONFIRMED_ROOT_CAUSE），不冒充结论。
--   三元组是内容面（计入 claim_hash，修订走 CAS 更新），不进 claim_fingerprint
--   身份面（V17 双哈希语义维持）。
--   授权：V17 已 grant select, insert, update on rca_claim to control_app——
--   表级授权自动覆盖新增列，无需重复授权（V37 kind 列同律）。
--   回滚：drop 三列（先滚应用后滚库——store 写列，应用先行回退即无写面）。
--   编号纪律：rebase 时以下一可用号为准替换 V147。
-- ============================================================================

alter table rca_claim add column root_component varchar(128);
alter table rca_claim add column root_fault_type varchar(64);
alter table rca_claim add column root_reason_code varchar(128);

comment on column rca_claim.root_component is
    'ROOT_CAUSE 断言的结构化评分面：canonical 根因组件码（null=模型未提供，诚实降级）';
comment on column rca_claim.root_fault_type is
    'ROOT_CAUSE 断言的结构化评分面：canonical 故障类型码（null=模型未提供，诚实降级）';
comment on column rca_claim.root_reason_code is
    'ROOT_CAUSE 断言的结构化评分面：canonical 根因机制码（null=模型未提供，诚实降级）';
