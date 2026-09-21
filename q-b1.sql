SELECT column_name FROM information_schema.columns
WHERE table_name ILIKE '%model_input%' OR table_name ILIKE '%input_capture%'
ORDER BY table_name, ordinal_position;
SELECT table_name FROM information_schema.tables
WHERE table_schema='public' AND (table_name ILIKE '%input%' OR table_name ILIKE '%model_call%')
ORDER BY 1;
