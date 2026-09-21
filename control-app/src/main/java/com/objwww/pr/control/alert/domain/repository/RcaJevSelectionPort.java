package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.RcaJevSelection;

import java.util.List;
import java.util.UUID;

/**
 * Jev 选材审计台账端口（JE-02，V166 rca_jev_selection）。append-only：选材决策
 * 事实一行一次，不修改不删除。写面失败由调用方有界降级（记 warn 不打断主链）——
 * 审计是增益不是依赖，与输入捕获同律。
 */
public interface RcaJevSelectionPort {

    /** 落一行（幂等不要求：每行 id 独立；主键冲突=调用方重复落，显式异常） */
    void append(RcaJevSelection row);

    /** run 级倒序读面（created_at desc，供前端选材明细卡；上限由实现裁剪） */
    List<RcaJevSelection> findByRun(UUID runId, int limit);

    /** 默认实现 = 无审计面环境（测试 fake/降级仓储）静默不落、读空 */
    RcaJevSelectionPort NO_OP = new RcaJevSelectionPort() {
        @Override
        public void append(RcaJevSelection row) {
        }

        @Override
        public List<RcaJevSelection> findByRun(UUID runId, int limit) {
            return List.of();
        }
    };
}
