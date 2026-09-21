-- BA-191：service.rollback 真执行极小放行——flagd paymentFailure 旗标回滚场景
-- （S27 变更回归处置链）。定谳背景：审批链（action_intent → approval_request →
-- 双人 grant → OperationPlanner → operation_outbox）已通，但执行面是 dry_run 模拟
-- ——V121 scoped unlock 注册表在档、service.rollback 无白名单行 = 永远 dry_run；
-- executor 端点按 action_id 路由的扩展点未建。本迁移建三元放行的数据面：
--   1. resource_inventory 补 flagd 旗标资源身份（flag://flagd/paymentFailure，
--      canonical_env=production）+ 请求键别名——planner 三元匹配（tool ×
--      resource_uid × canonical_env）的 env 比对依赖 inventory 权威面（V115：
--      身份只信本表，告警标签零授权效力）。注：本行声明的是评测/演练面 flagd
--      旗标资源这一新身份，非既有生产服务身份（生产服务清单仍归运维面维护）；
--   2. mutation_unlock_registry 放行 service.rollback × 该旗标资源 × production
--      ——注册表外仍永远 dry_run（最小暴露面：单工具 × 单资源 × 单环境；
--      enabled=false 即停用不删行，比删行可审计）；人工审批 mandatory 不变
--      （消费模板前置，不因解锁豁免）；
--   3. control_app 补 flagd_restore_ledger 读 + 状态收口列更新——V95 原授
--      eval_app 独占；真执行执行器（control-app 进程，docker profile）做条件
--      恢复需读可恢复记录 + close CAS 收口（与 driver/sweeper 三面恰一方）。
--      授权面与 eval_app 同律（select + 列级 update），不开 insert——激活落账
--      仍归 eval 注入面单一写身份。

-- 1. flagd 旗标资源身份（S27 处置链唯一放行对象；on conflict 幂等）
insert into resource_inventory (resource_uid, canonical_env, canonical_team, resource_kind, labels)
values ('flag://flagd/paymentFailure', 'production', 'payments', 'flag',
        '{"flagd":"paymentFailure","note":"BA-191 S27 变更回归处置链放行资源"}')
on conflict (resource_uid) do nothing;

insert into resource_alias (resource_key, resource_uid)
values ('flagd-paymentFailure', 'flag://flagd/paymentFailure')
on conflict (resource_key) do nothing;

comment on column resource_inventory.resource_kind is
    '资源类别（service/database/flag 等）；flag=BA-191 起 flagd 旗标资源（uid 形 flag://flagd/<name>）';

-- 2. 白名单行（service.rollback 仅放行该旗标资源；注册表外永远 dry_run）
insert into mutation_unlock_registry(unlock_id, tool_name, resource_uid, canonical_env, note)
values ('ba191191-0000-0000-0000-000000000191', 'service.rollback',
        'flag://flagd/paymentFailure', 'production',
        'BA-191：S27 变更回归处置链真执行放行——flagd paymentFailure 翻回 baseline/off；注册表外永远 dry_run')
on conflict (tool_name) do nothing;

-- 3. control_app 恢复台账读 + 收口面（与 V95 eval_app 授权面同律，不开 insert）
grant select on flagd_restore_ledger to control_app;
grant update (state, state_reason, updated_at) on flagd_restore_ledger to control_app;
