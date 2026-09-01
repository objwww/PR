package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.OutboxState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AFT-16（M2 方案 §11/L0，§4.3/EX-29）：repair payload 结构断言的静态对应物——
 * 反射读 {@link CommandPayloadValidator} 的私有拒绝清单，断言 raw URL/method/token/
 * installation 覆盖键在列；行为面（逐键 fail-closed 拒绝）由
 * {@code PublicationGateTest.repairPayloadOverrideKeysRejected} 穷举，二者互为动静态双钉。
 */
class CommandPayloadValidatorContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void forbiddenKeySetCoversRawAddressSecretAndInstallationOverride() throws Exception {
        Field setField = CommandPayloadValidator.class
                .getDeclaredField("FORBIDDEN_ADDRESS_OR_SECRET_KEYS");
        setField.setAccessible(true);
        Set<String> forbidden = (Set<String>) setField.get(null);

        // raw 寻址/动词/凭证键必须在拒绝清单（缺任一键 = repair payload 覆盖注入面开口）
        assertThat(forbidden).contains(
                "url", "uri", "api_url", "base_url", "method", "http_method", "endpoint",
                "token", "authorization", "password", "secret");
        // 唯一合法 installation 键是写前预检声明 installation_id——不得在拒绝集合，
        // 且必须等于私有常量 INSTALLATION_PRECHECK_KEY（防例外键被悄悄改名/扩面）
        assertThat(forbidden).doesNotContain("installation_id");
        Field precheckField = CommandPayloadValidator.class
                .getDeclaredField("INSTALLATION_PRECHECK_KEY");
        precheckField.setAccessible(true);
        assertThat(precheckField.get(null)).isEqualTo("installation_id");
    }

    @Test
    void installationPrefixedOverrideKeysRejectedByPrefixRule() {
        // 前缀规则的静态面钉样例键：installation* 覆盖键一律视为注入（清单之外的第二道）
        ClaimedCommand command = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        for (String key : List.of("installation", "installation_token", "installationid")) {
            Map<String, Object> payload = TestFixtures.checkPayload(command);
            payload.put(key, "injected-by-attacker");
            assertThat(CommandPayloadValidator.violations(command, payload))
                    .as("installation 覆盖键必须被拒: %s", key)
                    .anyMatch(violation -> violation.contains(key));
        }
        // 反向钉：合法预检声明 installation_id 不产生违例
        assertThat(CommandPayloadValidator.violations(
                command, TestFixtures.checkPayload(command))).isEmpty();
    }
}
