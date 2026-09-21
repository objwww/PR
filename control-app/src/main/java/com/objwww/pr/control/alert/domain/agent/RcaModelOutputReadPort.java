package com.objwww.pr.control.alert.domain.agent;

import java.util.List;
import java.util.UUID;

/**
 * 输出捕获读面端口（V167，/api/rca-runs/{runId}/model-outputs 的数据源）：
 * run 级按 action_seq 取每步调用的账本锚 + 输入/输出两侧捕获行——一次 join
 * 取齐（捕获行与账行 1:1），避免前端两段查询拼对。捕获未落行的一侧
 * （输入 DIGEST_ONLY 之外的缺失、输出 OFF 档或调用未成功）→ 该侧 null 如实，
 * 读面不造"空文本"假象。
 */
public interface RcaModelOutputReadPort {

    /** run 级步序读面（action_seq 升序；上限由调用方裁剪） */
    List<StepRow> byRun(UUID runId, int limit);

    /** 单侧捕获投影（level/text/digest/messageBytes/redactionNote；text 可空=DIGEST_ONLY 档） */
    record CaptureSide(String level, String text, String digest,
            Integer messageBytes, String redactionNote) {
    }

    /** 步级投影：账行锚（actionSeq/roleId/state）+ 输入/输出捕获（无行侧 null） */
    record StepRow(UUID modelCallId, long actionSeq, String roleId, String state,
            CaptureSide input, CaptureSide output) {
    }
}
