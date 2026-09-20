-- V157: eval_app 只读授权补齐——工具计数三段降级的两个真源面
--
-- p8 实证（2026-09-20，run 52f84916）：SingleCaseScorer 的 rca_evidence /
-- rca_tool_invocation 回退查询在 eval worker（连接身份 eval_app）下
-- permission denied，被评分侧 catch 吞成 tool_calls_total=0——前端工具调用
-- 列恒 0 的假面根因之一。V15/V16 建表时只 GRANT 给 control_app，eval 面
-- 从未补授权。补只读 SELECT；写面（eval_app 自有表）不受影响。

GRANT SELECT ON rca_tool_invocation TO eval_app;
GRANT SELECT ON rca_evidence TO eval_app;
