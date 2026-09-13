package com.objwww.pr.control.release.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill 每 Run 持久绑定行（CL-05，告警-Agent闭环修复 v1 §4.1，V100
 * rca_run_skill_binding）：每 (run, role, configEpoch) 至多一条的选择事实——
 * SELECTED 带不可变资产 digest，或明确 NONE（无匹配同样钉版，发布不追溯）。
 *
 * <p>configEpoch=0 兼容无代际史的存量 run（服务层把 null 代际映射为 0；行与
 * EN-04 播种代际同键空间但不同 run，无碰撞）。insert-if-absent 单写者：并发双
 * 首次撞 (run, role, epoch) 主键，败者读胜者返回；行落库后零改写（撤销走候选
 * RETIRED 消费阻断，不改写绑定历史）。
 */
public record SkillRunBinding(UUID runId,
                              String roleId,
                              long configEpoch,
                              String selectionStatus,
                              String assetDigest,
                              String releaseDigest,
                              String selectorVersion,
                              UUID sourceCommandId,
                              Instant createdAt) {

    public static final String SELECTED = "SELECTED";
    public static final String NONE = "NONE";

    public SkillRunBinding {
        Objects.requireNonNull(runId, "runId");
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalArgumentException("roleId 不得为 blank");
        }
        if (configEpoch < 0) {
            throw new IllegalArgumentException("configEpoch 不得为负");
        }
        if (!SELECTED.equals(selectionStatus) && !NONE.equals(selectionStatus)) {
            throw new IllegalArgumentException(
                    "selectionStatus 必须为 SELECTED/NONE: " + selectionStatus);
        }
        if (SELECTED.equals(selectionStatus) && !isDigest(assetDigest)) {
            throw new IllegalArgumentException("SELECTED 绑定必须携带 64 位 hex 资产 digest");
        }
        if (NONE.equals(selectionStatus) && assetDigest != null) {
            throw new IllegalArgumentException("NONE 绑定不得携带资产 digest");
        }
        if (!isDigest(releaseDigest)) {
            throw new IllegalArgumentException("releaseDigest 必须为 64 位 hex");
        }
        if (selectorVersion == null || selectorVersion.isBlank()) {
            throw new IllegalArgumentException("selectorVersion 不得为 blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean selected() {
        return SELECTED.equals(selectionStatus);
    }

    private static boolean isDigest(String value) {
        return value != null && value.length() == 64
                && value.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f'));
    }
}
