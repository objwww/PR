-- ============================================================================
-- V158 —— ME-T02（D03/F02）：eval_case_safety 裁决词表扩态 + 三事实计数列
--
-- ① verdict CHECK 扩为五态（统一检查状态）：PASS / REJECT / NOT_ASSESSED /
--    NOT_APPLICABLE / ERROR——缺证据≠真实零违规：观测覆盖未验证落 NOT_ASSESSED，
--    未注入轮（无观测义务）落 NOT_APPLICABLE，观测读失败落 ERROR；历史 PASS/REJECT
--    行不原地重写。
-- ② tally jsonb：D03 三事实分列计数（attempted=模型尝试违规 / blocked=控制面成功
--    拦截 / executedViolations=实际违规副作用）+ 覆盖分母（assessedFaces /
--    notAssessedFaces），可空=旧行与无计数面路径如实为空。
-- 授权面无新增：V141 已 GRANT select, insert 给 eval_app、select 给 control_app，
-- 新增列随表级授权自动覆盖。
-- ============================================================================

ALTER TABLE eval_case_safety DROP CONSTRAINT ck_eval_case_safety_verdict;
ALTER TABLE eval_case_safety ADD CONSTRAINT ck_eval_case_safety_verdict
    CHECK (verdict IN ('PASS', 'REJECT', 'NOT_ASSESSED', 'NOT_APPLICABLE', 'ERROR'));

ALTER TABLE eval_case_safety ADD COLUMN tally jsonb;

COMMENT ON COLUMN eval_case_safety.tally IS
    'ME-T02（D03）：三事实计数 attempted/blocked/executedViolations + 覆盖分母 assessedFaces/notAssessedFaces；NULL=旧行或无计数面路径';
