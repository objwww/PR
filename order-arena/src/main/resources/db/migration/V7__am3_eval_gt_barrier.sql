-- ============================================================================
-- V7 —— AM3 arena 域：GT 延迟授权 security-barrier（M3-10，评审 P0-6）
--   设计依据：AM3 v3.0 §6.4 评分面 / 落码方案 M3-10 / AM2 V3 授权矩阵修订。
--
-- 答案泄漏防线（eval_app 先看答案再解释输出的风险闭合）：
--   ① 撤销 eval_app 对 arena.ground_truth_scenario 的基表直接 SELECT（V3 L133 授予的本
--      里程碑收回）——评分器在输出冻结前连 GT 的存在性都不可见；
--   ② 改经 SECURITY DEFINER 函数 arena.eval_release_gt(run_id, scenario_id) 读取，
--      fail-closed 栅栏（任一不满足 = 空集，不抛错不泄露存在性）：
--        a. 场景绑定在案：arena.oa_scenario_map 存在 (scenario_id, run_id) 绑定行；
--        b. 绑定 Run 已终态：public.rca_run.state ∈ 终态四值；
--        c. 输出快照已冻结：public.rca_report 存在该 run 的报告行
--           （rca_report INSERT-only 不可变 = 冻结锚，BA-10②）。
--   冻结输出 → 获取 GT → 评分 的 CAS 顺序由此函数在 DB 面强制；应用侧在拿到非空 GT
--   前没有评分输入（拿不到答案）。
--
-- 授权边界：函数 EXECUTE 只授 eval_app；基表 direct SELECT 仍属 chaos_admin_app
-- （激活事务写者不变）；session/event 读取面不变（非答案载荷）。
-- ============================================================================

-- ① 收回基表直接读（V3 授予面；评分读面从本迁移起只走②的函数）
revoke select on arena.ground_truth_scenario from eval_app;

-- ② security-barrier 读取函数（owner = 迁移执行者，跨 schema 读绑定/终态/冻结锚）
create function arena.eval_release_gt(p_run_id uuid, p_scenario_id text)
    returns table (scenario_id           text,
                   activation_generation integer,
                   dataset_version       text,
                   config_digest         char(64),
                   payload_digest        char(64),
                   review_status         varchar(16))
    language plpgsql
    security definer
    stable
    set search_path = pg_catalog, arena as $$
begin
    return query
    select gt.scenario_id,
           gt.activation_generation,
           gt.dataset_version,
           gt.config_digest,
           gt.payload_digest,
           gt.review_status
    from arena.ground_truth_scenario gt
    where gt.scenario_id = p_scenario_id
      -- a. 场景绑定在案（C-6 归属链：scenario_map 是 run↔scenario 的唯一权威绑定）
      and exists (select 1
                  from arena.oa_scenario_map m
                  where m.scenario_id = gt.scenario_id
                    and m.run_id = p_run_id::text)
      -- b. 绑定 Run 已终态（QUEUED/RUNNING 视为调查仍在进行，不释放）
      and exists (select 1
                  from public.rca_run r
                  where r.id = p_run_id
                    and r.state in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'SUPERSEDED'))
      -- c. 输出快照已冻结（rca_report INSERT-only = 冻结锚）
      and exists (select 1 from public.rca_report rp where rp.run_id = p_run_id);
end;
$$;

-- 函数面只开 eval_app（其余角色含 PUBLIC 一律不可执行，防绕道）
revoke all on function arena.eval_release_gt(uuid, text) from public;
grant execute on function arena.eval_release_gt(uuid, text) to eval_app;
