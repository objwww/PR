package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.release.domain.model.SkillCandidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Skill 候选仓储（EN-08，V97）：(source_digest, name) 唯一 = S14 幂等锚
 * （重复提交生成作业返回既有行，不堆重复候选）。生命周期行可变（update 授权），
 * 历史只前进不改写。
 */
public interface SkillCandidateRepository {

    /** 新候选落行；false = 幂等键已存在（S14 重放面，调用方读既有行） */
    boolean insert(SkillCandidate candidate);

    Optional<SkillCandidate> findById(UUID id);

    /** 幂等键查面（S14：同源同名重放） */
    Optional<SkillCandidate> findBySource(String sourceDigest, String name);

    /** 生命周期推进写面（状态机在域类型，仓储只落行） */
    void update(SkillCandidate candidate);

    /** 状态过滤列表（activeSkills 生产读面 = listByStatus(ACTIVE)；S08 隔离面） */
    List<SkillCandidate> listByStatus(String status);
}
