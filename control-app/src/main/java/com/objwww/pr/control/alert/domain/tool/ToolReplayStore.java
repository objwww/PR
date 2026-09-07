package com.objwww.pr.control.alert.domain.tool;

import java.util.Optional;

/**
 * 回放记录端口（AM4 M4-32）：REPLAY_MOCK 的记录/查询面。记录键 = ActionDigest
 * （envelope 六字段 canonical sha256）——tool/version/args/time/snapshot 全相同才同键，
 * 任一字段不同即不同键（查不到 = REPLAY_MISS）。同键异响应的冲突拒绝由应用层
 * {@code ReplayToolGateway.record} 强制（禁静默覆盖），实现可再自证但不得放松。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public interface ToolReplayStore {

    /** 记录一条回放响应（同键同响应当幂等） */
    void put(ReplayRecord record);

    /** 按 action digest 精确查询（无记录返回空，绝不返回"最接近"的记录） */
    Optional<ReplayRecord> find(String actionDigest);

    /** 一条回放记录：action digest（精确匹配键）+ 工具身份 + 录制的响应字节 */
    record ReplayRecord(String actionDigest, String toolName, String toolVersion,
            byte[] response) {
    }
}
