package com.objwww.pr.arena.application;

import com.objwww.pr.arena.application.chaos.FaultGate;
import com.objwww.pr.arena.application.chaos.FaultType;
import com.objwww.pr.arena.domain.model.PaymentResult;

import java.math.BigDecimal;

/**
 * 支付网关端口——靶场内置确定性模拟世界（无真实渠道）：
 * <ul>
 *   <li>sku 以 "-declined" 结尾 → DECLINED（拒绝路径，M2-10）；</li>
 *   <li>F3 会话命中该 correlation → UNKNOWN（超时未知，持久化后走对账，禁 sleep，M2-20）；</li>
 *   <li>其余 → SUCCEEDED。</li>
 * </ul>
 * 迟到成功由 F3ReconcileService 驱动（UNKNOWN 记录上模拟回调补落），不经本接口。
 */
public class PaymentGatewaySimulator {

    private final FaultGate faultGate;

    public PaymentGatewaySimulator(FaultGate faultGate) {
        this.faultGate = faultGate;
    }

    public PaymentResult authorize(String correlationId, String sku, BigDecimal amount) {
        return decide(correlationId, sku);
    }

    public PaymentResult capture(String correlationId, String sku, BigDecimal amount) {
        return decide(correlationId, sku);
    }

    private PaymentResult decide(String correlationId, String sku) {
        if (sku != null && sku.endsWith("-declined")) {
            return PaymentResult.DECLINED;
        }
        if (faultGate.active(FaultType.F3, correlationId)
                && correlationId != null && correlationId.startsWith("chaos-")) {
            return PaymentResult.UNKNOWN;
        }
        return PaymentResult.SUCCEEDED;
    }
}
