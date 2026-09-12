package com.objwww.pr.control.alert.domain.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * R2 回放读面（评测/审计）：按 model_call_id 取捕获行并带出账本行对账锚——
 * 捕获行与账本行 1:1（model_call_id 主键投影），join 一次取齐，避免两段查询
 * 中间出现终态漂移窗。
 */
public interface RcaModelInputReplayPort {

    Optional<ReplayRow> byModelCallId(UUID modelCallId);

    /** 回放读投影（captureDigest/ledgerPromptDigest 供复算比对；roleDigest 供 MC36 版本反查） */
    record ReplayRow(UUID modelCallId, String captureLevel, String promptText,
            String captureDigest, String ledgerPromptDigest, String roleId,
            String roleDigest) {
    }
}
