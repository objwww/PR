-- ============================================================================
-- V151 —— 断言投影症状码存储面（告警-Agent 症状评分贯通修复，BA-148 同族）
--
--   背景：NativeReportAdapter 旧实现把 claim 的证据来源标签（logs/prometheus）
--   塞进 EvidencePackage v2 claims[].symptom_codes 槽位——评分契约
--   （expected_symptom_codes=告警名，如 ArenaDuplicateOrders）与来源标签结构性
--   恒 miss（195 生产实证：批 aa7f25b4 eval_case_result tp=0/fp=99/fn=60）。
--   审计字段冒充评分契约字段，与 V147 root_cause 三元组（BA-148）同族缺陷。
--   本迁移交存储面：主 Agent FINAL 提案的 symptom_codes（协议解析即有界——
--   条数上限复用 ReportClaim.MAX_SYMPTOM_CODES=32，条目 256 字符）经检查点 →
--   投影落本列，报告相位直填 claims[].symptom_codes 评分输入；来源信息改由
--   报告面新增 evidence_sources 键保留，不丢审计面。
--   可空：存量行与"模型未声明症状码"的新行均为 null = 诚实降级（报告面落
--   空数组），不拿来源标签冒充。
--   症状码是内容面（计入 claim_hash，修订走 CAS 更新），不进 claim_fingerprint
--   身份面（V17 双哈希语义维持，与 V147 三元组同律）。
--   授权：V17 已 grant select, insert, update on rca_claim to control_app——
--   表级授权自动覆盖新增列，无需重复授权（V37/V147 同律）。
--   回滚：drop 本列（先滚应用后滚库——store 写列，应用先行回退即无写面）。
--   编号纪律：rebase 时以下一可用号为准替换 V151。
-- ============================================================================

alter table rca_claim add column symptom_codes jsonb;

comment on column rca_claim.symptom_codes is
    'SYMPTOM 断言的症状码评分面：告警名数组（null=模型未声明，诚实降级——禁止来源标签冒充）';
