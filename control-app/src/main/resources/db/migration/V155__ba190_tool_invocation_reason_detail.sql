-- BA-190（W3）：rca_tool_invocation 补 reason_detail——工具拒因具体消息落账。
-- 定谳背景：账本只有 reason_code=INVALID_ARGS 等十码，BoundedLlmRoleRunner 的拒因
-- WARN 日志随容器重建丢失，事后无法回答"为什么被拒"。本列存 INVALID_ARGS/控制面
-- 拒绝的具体消息（应用侧截断 200 字符，与 EvalBatchRunner.abbreviate 同律）；
-- 可空 = 旧行与无详情路径（传输未知等）如实为空，不造详情。
-- V15 表级授权（grant select, insert, update on rca_tool_invocation to control_app）
-- 自动覆盖新增列，本迁移不新开授权面。
ALTER TABLE rca_tool_invocation ADD COLUMN reason_detail text;

COMMENT ON COLUMN rca_tool_invocation.reason_detail IS
    'BA-190：工具拒因具体消息（INVALID_ARGS/控制面拒绝等），应用侧截断 200 字符；NULL=无详情（旧行/传输未知族）';
