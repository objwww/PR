package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PrSubjectState;

import java.time.Instant;
import java.util.Objects;

/**
 * 投影同步命令（M1-T06，application 层值对象）：draft 廉价预检 / T-close / T-draft 三条
 * 路径共用的输入——远端（或 404 时事件载荷兜底）确认的事实 + 策略代 + LWW 水印推进值。
 *
 * <p>eventUpdatedAt 可空（EX-18：远端缺/非法 updated_at 时不造）：非 null 时随事务推进
 * 水印（GREATEST 条件更新，CT-14），为 null 跳过不覆盖。
 *
 * <p>policyVersion 仅在"投影行尚不存在需新建"时作为 current_policy_version 落库
 * （V1 ck 非空约束）；策略代本身不属于 draft/close 的语义，首个真实 T1 会以
 * revision 切换触发换届（epoch+1），不受此占位影响。
 */
public record ProjectionSyncCommand(
        long installationId,
        long repositoryId,
        String repositoryFullName,
        int prNumber,
        PrSubjectState prState,
        boolean draft,
        boolean merged,
        String policyVersion,
        Instant eventUpdatedAt) {

    public ProjectionSyncCommand {
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(prState, "prState");
        Objects.requireNonNull(policyVersion, "policyVersion");
        // eventUpdatedAt 刻意允许 null（EX-18）
    }
}
