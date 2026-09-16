-- ITSM 工单草稿（业界路线v2第6项第一阶段）：草稿落库可导出；真推送待外部系统配置
CREATE TABLE IF NOT EXISTS itsm_ticket (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    system_name TEXT NOT NULL DEFAULT 'DRAFT_EXPORT',
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    priority TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'DRAFT',
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_itsm_ticket_created ON itsm_ticket (created_at DESC);
GRANT SELECT, INSERT ON itsm_ticket TO control_app;
