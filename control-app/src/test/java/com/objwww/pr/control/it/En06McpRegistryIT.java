package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository.ServerRecord;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository.ServerSpec;
import com.objwww.pr.control.infrastructure.persistence.PostgresMcpServerRegistryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN-06 真 PG 数据面（L1，195 窗真值；本机无 docker 自动跳过）：mcp_server_registry
 * 仓储钉三件事——generation 全表单调递增且 loadAll 按代际序返回、同名 upsert 幂等顶替
 * 不新增行（M12 重复注册幂等的持久面）、删除面真删；control_app 角色真跑授权面
 * （V64：select,insert,update,delete）。
 */
class En06McpRegistryIT extends PostgresITBase {

    private PostgresMcpServerRegistryRepository repository() {
        return new PostgresMcpServerRegistryRepository(controlJdbc);
    }

    @Test
    @DisplayName("generation 单调递增，loadAll 按代际序（M12 一致 revision 的持久前提）")
    void upsertAssignsMonotonicGenerationAndLoadAllOrdersByIt() {
        PostgresMcpServerRegistryRepository repository = repository();
        ServerSpec kit = new ServerSpec("alert-kit", "streamable_http",
                "http://127.0.0.1:9999/mcp", List.of(), null, true);
        ServerSpec stdio = new ServerSpec("kit-stdio", "stdio",
                "mcp-server-kit", List.of("--transport", "http://127.0.0.1:9999/mcp"), null, false);

        ServerRecord first = repository.upsert(kit);
        ServerRecord second = repository.upsert(stdio);

        assertThat(second.generation()).isGreaterThan(first.generation());
        assertThat(repository.loadAll()).extracting(ServerRecord::generation)
                .containsExactly(first.generation(), second.generation());
        assertThat(repository.loadAll().get(1).spec().name()).isEqualTo("kit-stdio");
        assertThat(repository.loadAll().get(1).spec().args())
                .containsExactly("--transport", "http://127.0.0.1:9999/mcp");
    }

    @Test
    @DisplayName("同名 upsert 幂等顶替不新增行（M12 重复注册幂等的持久面）")
    void duplicateNameUpsertReplacesRowIdempotently() {
        PostgresMcpServerRegistryRepository repository = repository();
        ServerSpec original = new ServerSpec("alert-kit", "streamable_http",
                "http://old/mcp", List.of(), null, true);

        ServerRecord created = repository.upsert(original);
        ServerRecord replaced = repository.upsert(new ServerSpec("alert-kit", "streamable_http",
                "http://new/mcp", List.of(), null, false));

        assertThat(count("mcp_server_registry")).isEqualTo(1);
        assertThat(replaced.generation()).isGreaterThan(created.generation());
        ServerRecord loaded = repository.loadAll().get(0);
        assertThat(loaded.spec().endpoint()).isEqualTo("http://new/mcp");
        assertThat(loaded.spec().enabled()).isFalse();
    }

    @Test
    @DisplayName("deregister 删除面真删；disabled 行保留（M12 不复活的前提是行还在）")
    void deleteRemovesRowAndKeepsOthers() {
        PostgresMcpServerRegistryRepository repository = repository();
        ServerSpec kit = new ServerSpec("alert-kit", "streamable_http",
                "http://old/mcp", List.of(), null, true);
        ServerSpec other = new ServerSpec("other", "streamable_http",
                "http://other/mcp", List.of(), null, false);
        repository.upsert(kit);
        repository.upsert(other);

        assertThat(repository.delete("alert-kit")).isTrue();
        assertThat(repository.delete("alert-kit")).as("重复删除幂等返回 false").isFalse();

        assertThat(count("mcp_server_registry")).isEqualTo(1);
        assertThat(repository.loadAll().get(0).spec().name()).isEqualTo("other");
        assertThat(repository.loadAll().get(0).spec().enabled()).isFalse();
    }
}
