package com.objwww.pr.control.domain.model;

/** artifact 表 ck_artifact_type 枚举镜像（v2.2 §5） */
public enum ArtifactType {
    SOURCE_SNAPSHOT,
    DIFF_BUNDLE,
    FINDING_BODY,
    REVIEW_PAYLOAD,
    WEBHOOK_PAYLOAD,
    MODEL_RESPONSE
}
