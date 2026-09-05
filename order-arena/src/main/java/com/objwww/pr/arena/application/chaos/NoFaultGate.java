package com.objwww.pr.arena.application.chaos;

import java.util.Optional;

/**
 * 阶段二（正常链）装配的恒否 Gate：无 chaos 域时的确定性行为，
 * 保证"无 ACTIVE 行 = 不注入"在组件缺席时依然成立（fail-closed 的空实现面）。
 */
public class NoFaultGate implements FaultGate {

    @Override
    public Optional<ActiveFault> probe(FaultType type, String correlationId) {
        return Optional.empty();
    }
}
