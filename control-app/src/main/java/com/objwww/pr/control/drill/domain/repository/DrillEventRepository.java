package com.objwww.pr.control.drill.domain.repository;

import com.objwww.pr.control.drill.domain.model.DrillEvent;

import java.util.List;
import java.util.UUID;

/**
 * DR-02 演练事件仓储（V86 drill_event，insert-only 账本）：
 * control_app 与 eval_app 都只有 select,insert——事件落库即冻结，零 update/delete
 * 开口（授权面 V86 收口）。seq 由库 identity 生成（单调序 = 事件游标锚）。
 */
public interface DrillEventRepository {

    void insert(DrillEvent event);

    /** 按 seq 升序（时间线推导与事件投影共用同一有序面） */
    List<DrillEvent> listByDrill(UUID drillId);

    /**
     * 游标增量读（方案 §7.2 事件流端点 / DU10 有界轮询的读面）：seq 严格大于
     * afterSeq 的最早 limit 条，按 seq 升序。游标锚 = seq（库 identity 单调序），
     * 浏览器重开从 afterSeq=0 整页重拉即状态一致，不依赖客户端记忆。
     */
    List<DrillEvent> listByDrillAfter(UUID drillId, long afterSeq, int limit);
}
