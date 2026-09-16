-- 驳回结构化（前端产品化波次3）：分类词表 + 真实根因（自由文本，进反馈环）
ALTER TABLE conclusion_feedback ADD COLUMN IF NOT EXISTS category text;
ALTER TABLE conclusion_feedback ADD COLUMN IF NOT EXISTS actual_cause text;
