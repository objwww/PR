package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaRun;

import java.util.Optional;
import java.util.UUID;

/**
 * 调查 Run 只读源（EN-08 源头提炼的最小读面）：release 域不依赖 alert 应用层，
 * 只读 run 终态判定所需的最小投影（RcaRunSource vs 全量 RcaRunRepository——
 * 零框架端口同律，装配点以仓储方法引用承接）。
 */
@FunctionalInterface
public interface RcaRunSource {

    Optional<RcaRun> byId(UUID runId);
}
