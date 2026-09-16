-- 值班交接记录（业界对齐 Rootly/incident.io Handover）：结构化从/到/时间窗 + 手写备注；
-- 自动事实（当班期间新开/未决事故数等）在创建时由后端实测注入 content_stats jsonb
CREATE TABLE IF NOT EXISTS duty_handover (
    id UUID PRIMARY KEY,
    from_oncall TEXT NOT NULL,
    to_oncall TEXT,
    shift_start TIMESTAMPTZ,
    shift_end TIMESTAMPTZ,
    notes TEXT NOT NULL,
    content_stats JSONB,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_duty_handover_created ON duty_handover (created_at DESC);
GRANT SELECT, INSERT ON duty_handover TO control_app;
