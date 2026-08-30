package com.objwww.pr.publisher.interfaces.token;

import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.TokenScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只读 token 签发窄接口（T14）：共享密钥头拒绝/放行（MockMvc standalone）。
 * 密钥未配置 = fail-closed 全 401；放行路径调 broker 的 READ scope。
 */
class ReadOnlyTokenControllerTest {

    /** 记录调用的假 broker */
    private static final class FakeBroker implements CredentialBroker {
        final AtomicLong lastInstallationId = new AtomicLong();
        final AtomicReference<TokenScope> lastScope = new AtomicReference<>();

        @Override
        public String token(TokenScope scope) {
            return token(0L, scope);
        }

        @Override
        public String token(long installationId, TokenScope scope) {
            lastInstallationId.set(installationId);
            lastScope.set(scope);
            return "readonly-minted";
        }
    }

    private FakeBroker broker;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        mvc = MockMvcBuilders.standaloneSetup(new ReadOnlyTokenController(broker, "s3cret")).build();
    }

    @Test
    void rejectsMissingSecretHeader() throws Exception {
        mvc.perform(get("/internal/tokens/readonly").param("installation_id", "42"))
                .andExpect(status().isUnauthorized());
        assertThat(broker.lastScope.get()).isNull(); // 未触达 broker
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        mvc.perform(get("/internal/tokens/readonly")
                        .param("installation_id", "42")
                        .header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        assertThat(broker.lastScope.get()).isNull();
    }

    @Test
    void issuesReadOnlyTokenWithCorrectSecret() throws Exception {
        mvc.perform(get("/internal/tokens/readonly")
                        .param("installation_id", "42")
                        .header("X-Internal-Token", "s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("readonly-minted"));

        assertThat(broker.lastInstallationId.get()).isEqualTo(42L);
        assertThat(broker.lastScope.get()).isEqualTo(TokenScope.READ); // 窄接口只签发只读
    }

    @Test
    void blankConfiguredSecretFailsClosed() throws Exception {
        MockMvc unconfigured = MockMvcBuilders
                .standaloneSetup(new ReadOnlyTokenController(broker, " ")).build();

        unconfigured.perform(get("/internal/tokens/readonly")
                        .param("installation_id", "42")
                        .header("X-Internal-Token", " "))
                .andExpect(status().isUnauthorized());
        assertThat(broker.lastScope.get()).isNull();
    }
}
