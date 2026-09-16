-- 前端产品化波次2：AI 结论人工标注（确认/驳回）——precision/采纳率指标的数据源
-- 每条标注 = 一次人类对 AI 结论的裁决；驳回即反馈环输入（Bits correction 同型）。

CREATE TABLE conclusion_feedback (
    id             uuid PRIMARY KEY,
    incident_id    uuid NOT NULL REFERENCES incident (id),
    run_id         uuid,
    verdict        text NOT NULL,
    actor          text NOT NULL,
    reason         text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_conclusion_feedback_verdict CHECK (verdict IN ('CONFIRMED', 'REJECTED'))
);

CREATE INDEX idx_conclusion_feedback_incident ON conclusion_feedback (incident_id);
CREATE INDEX idx_conclusion_feedback_created ON conclusion_feedback (created_at);

COMMENT ON TABLE conclusion_feedback IS 'AI 结论人工标注（波次2）：verdict=CONFIRMED 确认 / REJECTED 驳回；驳回原因为必填面（前端强制）';
