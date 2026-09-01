package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;

/** 状态型 CHECK_RUN 可自动收敛；内容型及未知资源一律人工，fail-closed。 */
public final class RepairPolicy {
    public RepairPolicyTier decide(PublicationResourceType type) {
        return type == PublicationResourceType.CHECK_RUN
                ? RepairPolicyTier.AUTO : RepairPolicyTier.MANUAL;
    }

    public RepairPolicyTier decide(String type) {
        try {
            return decide(PublicationResourceType.valueOf(type));
        } catch (Exception ignored) {
            return RepairPolicyTier.MANUAL;
        }
    }
}
