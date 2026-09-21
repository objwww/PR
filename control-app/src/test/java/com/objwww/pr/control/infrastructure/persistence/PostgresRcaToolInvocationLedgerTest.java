package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-190（W3）工具拒因消息持久化的纯函数/契约面：
 * reason_detail 截断（200 字符，EvalBatchRunner.abbreviate 同律）与接口四参 fail
 * 的默认委托（假件/桩零漂移——未覆盖四参的实现详情丢弃但结算语义不变）。
 * 落库行为由 Testcontainers IT 覆盖。
 */
class PostgresRcaToolInvocationLedgerTest {

    @Test
    @DisplayName("截断：null 原样（不落空串冒充）；≤200 原样；>200 截到 200")
    void abbreviateTruncatesAt200() {
        assertThat(PostgresRcaToolInvocationLedger.abbreviate(null)).isNull();
        assertThat(PostgresRcaToolInvocationLedger.abbreviate("")).isEmpty();
        assertThat(PostgresRcaToolInvocationLedger.abbreviate("x".repeat(200)))
                .hasSize(200);
        assertThat(PostgresRcaToolInvocationLedger.abbreviate("y".repeat(201)))
                .hasSize(200)
                .endsWith("y");
        assertThat(PostgresRcaToolInvocationLedger.abbreviate("z".repeat(500)))
                .isEqualTo("z".repeat(200));
    }

    @Test
    @DisplayName("接口四参 fail 默认委托三参：旧假件零漂移（详情丢弃，结算照常）")
    void defaultFourArgFailDelegatesToThreeArg() {
        UUID op = UUID.randomUUID();
        boolean[] called = {false};
        RcaToolInvocationLedger stub = new RcaToolInvocationLedger() {
            @Override
            public void open(InvocationIdentity identity) {
            }

            @Override
            public boolean succeed(UUID operationId) {
                return false;
            }

            @Override
            public boolean fail(UUID operationId, ToolInvocationState terminal,
                                ToolReasonCode reasonCode) {
                called[0] = operationId.equals(op)
                        && terminal == ToolInvocationState.FAILED
                        && reasonCode == ToolReasonCode.INVALID_INPUT;
                return called[0];
            }
        };

        assertThat(stub.fail(op, ToolInvocationState.FAILED, ToolReasonCode.INVALID_INPUT,
                "未声明字段: fromm")).isTrue();
        assertThat(called[0]).isTrue();
    }
}
