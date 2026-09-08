\echo == 迁移历史 ==
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;
\echo == 表存在 ==
SELECT to_regclass('run_fallback'), to_regclass('report_generation_winner');
\echo == run_fallback 列清单 ==
SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name='run_fallback' ORDER BY ordinal_position;
\echo == run_fallback 约束（uq_rf_source 唯一 + depth<=1 check + deferred FK） ==
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='run_fallback'::regclass ORDER BY conname;
\echo == winner 约束（复合 PK） ==
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='report_generation_winner'::regclass ORDER BY conname;
\echo == run_fallback control_app 授权（仅 INSERT/SELECT） ==
SELECT privilege_type FROM information_schema.role_table_grants WHERE table_name='run_fallback' AND grantee='control_app' ORDER BY privilege_type;
\echo == winner control_app 授权（仅 INSERT/SELECT） ==
SELECT privilege_type FROM information_schema.role_table_grants WHERE table_name='report_generation_winner' AND grantee='control_app' ORDER BY privilege_type;
\echo == 其他角色应无授权（计数为 0） ==
SELECT count(*) FROM information_schema.role_table_grants WHERE table_name IN ('run_fallback','report_generation_winner') AND grantee IN ('publisher','notify','eval','public');
\echo == 序列 USAGE ==
SELECT has_sequence_privilege('control_app','run_fallback_id_seq','USAGE');
\echo == 行数（均应为 0） ==
SELECT (SELECT count(*) FROM run_fallback) AS run_fallback, (SELECT count(*) FROM report_generation_winner) AS winner;
