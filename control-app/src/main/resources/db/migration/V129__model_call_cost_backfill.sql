-- 模型调用成本回算（成本归因波次）：按 model_pricing 价目回填历史行 cost_micros，
-- requested_model 无价目/usage 缺失的行保持 NULL（诚实 usage_missing，不冒充 0）
UPDATE rca_model_call m
   SET cost_micros = (
         (m.usage->>'prompt_tokens')::bigint * p.input_micros_per_1k
         + COALESCE((m.usage->>'completion_tokens')::bigint, 0) * p.output_micros_per_1k
       ) / 1000,
       pricing_version = p.pricing_version,
       currency = p.currency
  FROM model_pricing p
 WHERE m.requested_model = p.model
   AND m.cost_micros IS NULL
   AND m.usage->>'prompt_tokens' IS NOT NULL;
