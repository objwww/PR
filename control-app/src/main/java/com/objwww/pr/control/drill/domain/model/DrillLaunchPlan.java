package com.objwww.pr.control.drill.domain.model;

import com.objwww.pr.shared.Digest;

import java.util.Objects;

/**
 * DR-02 演练发起计划（POST /api/drills 与 /preview 请求体的领域形；drill_job.params
 * 冻结内容与幂等冲突判据来源）。
 *
 * <p>白名单纪律（§7.2/DU04，服务端强制，不依赖前端范围控件）：durationSeconds 落
 * 模板区间、trafficScale 落模板枚举、targetEnv 落部署白名单、linkedEvalVersion 仅
 * 可选版本串——不接收服务器 IP/任意 SQL/shell。场景相关校验需模板，在
 * DrillJobService 完成；本 record 只做形态校验。
 */
public record DrillLaunchPlan(String scenarioId,
                              String targetEnv,
                              Integer durationSeconds,
                              String trafficScale,
                              String linkedEvalVersion) {

    public DrillLaunchPlan {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId 必填");
        }
        if (targetEnv == null || targetEnv.isBlank()) {
            throw new IllegalArgumentException("targetEnv 必填");
        }
        if (durationSeconds != null && durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds 必须为正");
        }
        if (linkedEvalVersion != null && linkedEvalVersion.length() > 64) {
            throw new IllegalArgumentException("linkedEvalVersion ≤64 字符");
        }
    }

    /** 固定字段序 canonical 行（空值以字面 null 参与——"未填"与"填默认值"可区分） */
    public String canonical() {
        return "drill-launch/v1"
                + "|scenarioId=" + scenarioId
                + "|targetEnv=" + targetEnv
                + "|durationSeconds=" + Objects.toString(durationSeconds, "null")
                + "|trafficScale=" + Objects.toString(trafficScale, "null")
                + "|linkedEvalVersion=" + Objects.toString(linkedEvalVersion, "null");
    }

    /** 幂等冲突判据（uq 撞键后：同 digest = 重放，异 digest = 409） */
    public Digest payloadHash() {
        return Digest.sha256Of(canonical());
    }
}
