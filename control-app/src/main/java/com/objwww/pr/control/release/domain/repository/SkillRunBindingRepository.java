package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.release.domain.model.SkillRunBinding;

import java.util.Optional;
import java.util.UUID;

/**
 * Skill 每 Run 绑定仓储（CL-05，V100）：只追加不修订——insertIfAbsent 撞
 * (run, role, epoch) 主键返回 false（并发双首选取一，败者读胜者）。实现方每方法
 * 自含短事务；热切同事务生成走调用方事务内直接 SQL 的组合面。
 */
public interface SkillRunBindingRepository {

    /** 落绑定行；false = 该 (run, role, epoch) 已有事实（胜者在库，读它） */
    boolean insertIfAbsent(SkillRunBinding binding);

    /** 选择事实读面（装配每次动作先查此口；不存在 = 尚未选择，走懒生成） */
    Optional<SkillRunBinding> find(UUID runId, String roleId, long configEpoch);
}
