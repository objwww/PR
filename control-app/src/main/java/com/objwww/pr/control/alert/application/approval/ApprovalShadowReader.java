package com.objwww.pr.control.alert.application.approval;

import java.util.Map;

/**
 * shadow 观测读面（PC-C3，§5 Phase C 观测清单的计数投影）：审批 correctness
 * （approved/denied/expired 分布）、scope 正确性消费拒绝（NO_GRANT 族由事件面承载）、
 * human latency（human_wait 合计/均值）。Phase C 验收 = 指标在位且从事件/账本可解释。
 */
public interface ApprovalShadowReader {

    Map<String, Object> summary();
}
