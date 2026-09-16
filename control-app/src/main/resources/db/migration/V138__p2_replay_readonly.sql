-- ============================================================================
-- V138 —— P2 执行集接通：回放案例读 alert_inbox 冻结原始载荷（只读授权）
--
-- 回放执行面（业界对标 P2：materialize 入集案例成为可执行场景，OpenRCA/
-- Meta point-in-time 回放形态）：ReplayScenarioDriver 从 alert_inbox 取含目标
-- alertname 的历史 firing 载荷原文，向 /webhooks/alertmanager 重投（新 HMAC
-- nonce/时间戳或 bearer 面），管线照常铸新 episode → 调查 → 评分对 GT。
--
-- 纪律：
--   - eval_app 只读（select）——不写生产告警域任何一行（V11 同律）；
--   - payload_raw 为告警运营数据（labels/annotations），非凭据面；
--   - 重放请求本身经 webhook 机器 bearer 认证（env 注入，INV-AM3-3 同律），
--     eval 侧未配置 bearer 时驱动器 fail-closed 拒绝激活。
-- ============================================================================

grant select on alert_inbox to eval_app;
