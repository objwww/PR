\echo == 迁移历史 ==
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;
\echo == 表存在 ==
SELECT to_regclass('engine_comparison');
\echo == 列清单 ==
SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name='engine_comparison' ORDER BY ordinal_position;
\echo == 唯一约束 ==
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='engine_comparison'::regclass;
\echo == control_app 授权 ==
SELECT privilege_type FROM information_schema.role_table_grants WHERE table_name='engine_comparison' AND grantee='control_app' ORDER BY privilege_type;
\echo == 序列 USAGE ==
SELECT has_sequence_privilege('control_app','engine_comparison_id_seq','USAGE');
\echo == 行数（应为 0）==
SELECT count(*) FROM engine_comparison;
