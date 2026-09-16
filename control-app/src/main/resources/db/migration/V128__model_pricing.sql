-- 模型定价表（前端产品化波次5，业界对齐 Langfuse/Helicone：定价配置+成本归因）
-- 单位：micros（百万分之一元）/千 token；读侧 JOIN 即时计价，历史与新增统一口径
CREATE TABLE IF NOT EXISTS model_pricing (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model TEXT NOT NULL UNIQUE,
    input_micros_per_1k BIGINT NOT NULL,
    output_micros_per_1k BIGINT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'CNY',
    pricing_version TEXT NOT NULL DEFAULT 'pricing-v1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON model_pricing TO control_app;

-- 在用模型种子价（阿里云百炼公开牌价口径，可 UPDATE 调整）
INSERT INTO model_pricing (model, input_micros_per_1k, output_micros_per_1k)
VALUES
    ('qwen-plus',              800, 2000),
    ('qwen3-max',             6000, 24000),
    ('deepseek-v4-flash-0731', 1000, 8000)
ON CONFLICT (model) DO NOTHING;
