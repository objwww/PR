package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-18 原因码穷举 UT：冻结十码与 Java 枚举面一致（多一码少一码都算漂移）；
 * 账本四态冻结。
 */
class ToolReasonCodeTest {

    @Test
    void utC01_原因码冻结十码_枚举面一致() {
        assertThat(Arrays.stream(ToolReasonCode.values()).map(Enum::name)
                .collect(Collectors.toSet()))
                .isEqualTo(ToolReasonCode.frozenCodeSet());
        assertThat(ToolReasonCode.values()).hasSize(10);
    }

    @Test
    void utC02_账本四态冻结_无审批挂起态() {
        assertThat(Arrays.stream(ToolInvocationState.values()).map(Enum::name))
                .containsExactly("PENDING", "SUCCESS", "FAILED", "UNKNOWN");
    }
}
