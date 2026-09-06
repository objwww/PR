package com.objwww.pr.control.alert.domain.repository;

import java.util.List;
import java.util.UUID;

/**
 * RCA 状态扫描端口（M4-03 状态回填/校验作业的数据面）：
 * 键集分页（id 升序、afterId 断点）逐批读出两张表的原始状态串——
 * 作业只读不写，中断后重跑即恢复（幂等对账）。
 *
 * <p>返回原始字符串而非枚举：解析一律交 {@code RcaStateContract}（fail-closed），
 * 契约外取值由作业收集为违规行而不是抛断整批。
 */
public interface RcaStateScanRepository {

    /** rca_task 状态批扫描：id > afterId（null = 从头）升序取 limit 行 */
    List<StateRow> scanTaskStates(UUID afterId, int limit);

    /** rca_run 状态批扫描：同上 */
    List<StateRow> scanRunStates(UUID afterId, int limit);

    record StateRow(UUID id, String state) {
    }
}
