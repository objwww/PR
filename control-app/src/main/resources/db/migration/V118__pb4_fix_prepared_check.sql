-- PB-BUG（195 演示窗当场暴露）：V116 的 ck_rca_operation_prepared 方向写反——
-- 原约束要求 PREPARED 态 prepared_at 为空、其余态非空，与域语义相反
-- （RcaOperation.prepare() 铸造即盖 prepared_at；withStatus 恒携带）。
-- 修正：铸造即有时点（非空单向约束），时间面单调性由域状态机承担。
--
-- 设计基线：§2.11（状态机时点面）；本修正随 V117 演示探针发现，BA 台账登记。

alter table rca_operation drop constraint ck_rca_operation_prepared;
alter table rca_operation add constraint ck_rca_operation_prepared
    check (prepared_at is not null);
