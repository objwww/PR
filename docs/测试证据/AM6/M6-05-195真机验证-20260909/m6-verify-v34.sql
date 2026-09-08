-- M6-05 V34 八段验表（195 真 PG；stdin 脚本，psql on 195 纪律）
\echo '== 1. flyway 迁移账（V34 success） =='
SELECT version, description, success, installed_on
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
\echo '== 2. holmes_shadow_work 列面 =='
SELECT column_name, data_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_name = 'holmes_shadow_work' ORDER BY ordinal_position;
\echo '== 3. 约束面（kind/state 值域 + generation/attempts 检查 + shadow_key 唯一） =='
SELECT conname, pg_get_constraintdef(oid) AS def
  FROM pg_constraint WHERE conrelid = 'holmes_shadow_work'::regclass ORDER BY conname;
\echo '== 4. 索引面（唯一键 + 认领序 ix_hsw_claim） =='
SELECT indexname, indexdef FROM pg_indexes
 WHERE tablename = 'holmes_shadow_work' ORDER BY indexname;
\echo '== 5. control_app 授权面（select/insert/update=t，delete=f——V34 差异化） =='
SELECT has_table_privilege('control_app','holmes_shadow_work','SELECT')  AS can_select,
       has_table_privilege('control_app','holmes_shadow_work','INSERT')  AS can_insert,
       has_table_privilege('control_app','holmes_shadow_work','UPDATE')  AS can_update,
       has_table_privilege('control_app','holmes_shadow_work','DELETE')  AS can_delete;
\echo '== 6. 序列 USAGE 面（BA-42①：bigserial 默认值写入前提） =='
SELECT has_sequence_privilege('control_app','holmes_shadow_work_id_seq','USAGE') AS seq_usage;
\echo '== 7. 其他角色零授权面 =='
SELECT has_table_privilege('publisher_app','holmes_shadow_work','SELECT') AS pub_sel,
       has_table_privilege('notify_app','holmes_shadow_work','SELECT')    AS notify_sel,
       has_table_privilege('eval_app','holmes_shadow_work','SELECT')      AS eval_sel;
\echo '== 8. FK 面与注释（native_run_id/incident_id 真 FK） =='
SELECT confrelid::regclass AS ref_table, conname
  FROM pg_constraint
 WHERE conrelid = 'holmes_shadow_work'::regclass AND contype = 'f' ORDER BY conname;
SELECT obj_description('holmes_shadow_work'::regclass) AS table_comment;
