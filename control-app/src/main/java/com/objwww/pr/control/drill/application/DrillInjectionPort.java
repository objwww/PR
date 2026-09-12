package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;

/**
 * DR-02 故障注入端口（worker 执行面；§7.4"远端 on 超时记 ACTION_UNKNOWN"的结果三态）：
 * <ul>
 *   <li>{@link Kind#NOT_PERFORMED}：注入确定未执行（零副作用）→ INJECTING→FAILED
 *       合法，不冒进恢复路径；</li>
 *   <li>{@link Kind#PERFORMED}：激活回执已得（身份/代次稳定）→ INJECTING→OBSERVING；</li>
 *   <li>{@link Kind#UNKNOWN}：注入结果无法判定（如 on 超时响应丢失）→ 必先进
 *       RECOVERING（一旦注入可能发生，停止/失败都先走恢复路径）。</li>
 * </ul>
 * 实现面：{@link CompositeDrillInjection} = DR-03 真实接线（按模板 driver 分派
 * arena-chaos/flagd 适配器，生产装配替换 EvalRunnerConfig 的 NotImplemented bean
 * 归收口留档）；{@link NotImplemented} 保留为未接线装配面的如实拒绝——
 * 确定零副作用，不假装注入成功。
 */
public interface DrillInjectionPort {

    enum Kind {NOT_PERFORMED, PERFORMED, UNKNOWN}

    record Outcome(Kind kind, String reason, String receiptJson) {

        public static Outcome notPerformed(String reason) {
            return new Outcome(Kind.NOT_PERFORMED, reason, null);
        }

        public static Outcome performed(String receiptJson) {
            return new Outcome(Kind.PERFORMED, null, receiptJson);
        }

        public static Outcome unknown(String reason) {
            return new Outcome(Kind.UNKNOWN, reason, null);
        }
    }

    Outcome inject(DrillJob job);

    /** 未接线装配面的默认实现：确定零副作用（NOT_PERFORMED 如实卡因） */
    final class NotImplemented implements DrillInjectionPort {

        public static final String REASON =
                "INJECTION_NOT_IMPLEMENTED: 故障注入执行接线未交付（DR-03/DR-04 卡）——"
                        + "确定未发生任何注入副作用，作业按零副作用 FAILED，不假装注入成功";

        @Override
        public Outcome inject(DrillJob job) {
            return Outcome.notPerformed(REASON);
        }
    }
}
