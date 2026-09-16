-- 诊断会话（业界路线v2第2项 v1：引用式问答，零模型调用零幻觉；问答落库可回放）
CREATE TABLE IF NOT EXISTS diag_session (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    question_key TEXT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    answer_refs JSONB,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_diag_session_incident ON diag_session (incident_id, created_at DESC);
GRANT SELECT, INSERT ON diag_session TO control_app;
