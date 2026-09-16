-- 通知静默窗口（前端产品化波次4，业界对齐：PagerDuty maintenance window /
-- Grafana OnCall silence）：命中规则的告警在窗口内不产通知 outbox（报告发布不受影响）
CREATE TABLE IF NOT EXISTS notify_silence (
    id UUID PRIMARY KEY,
    alertname TEXT,
    service TEXT,
    reason TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX IF NOT EXISTS idx_notify_silence_active
    ON notify_silence (expires_at) WHERE state = 'ACTIVE';
