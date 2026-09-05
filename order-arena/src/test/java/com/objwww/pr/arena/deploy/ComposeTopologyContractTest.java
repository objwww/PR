package com.objwww.pr.arena.deploy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM2 部署静态门（M2-02 验收）：deploy/alert compose 的 C-3 拓扑 + 安全项断言。
 * docker compose config --quiet 在 195 部署门执行（本机无 docker）；此处做
 * YAML 层等价静态断言，进 CI/部署门两道。
 *
 * <p>断言面（冻结裁定 C-3）：
 * <ul>
 *   <li>order-arena 只入 alert-net，mem_limit=512m（M2-01 冻结）；</li>
 *   <li>arena-chaos-admin 只入 eval-mgmt、零宿主端口；</li>
 *   <li>holmesgpt 不入 eval-mgmt（管理面对告警执行面不可达的拓扑前提）；</li>
 *   <li>arena-migrate 单一事实源直挂 order-arena 迁移目录、owner=postgres、schema=arena；</li>
 *   <li>主栈 postgres 加入 alert-net + eval-mgmt（arena 两角色触库的唯一通路）。</li>
 * </ul>
 */
class ComposeTopologyContractTest {

    private static Map<String, Object> alertCompose;
    private static Map<String, Object> mainCompose;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Yaml yaml = new Yaml();
        alertCompose = yaml.load(Files.readString(
                Path.of("../deploy/alert/docker-compose.yml"), StandardCharsets.UTF_8));
        mainCompose = yaml.load(Files.readString(
                Path.of("../deploy/docker-compose.yml"), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(Map<String, Object> compose, String name) {
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        assertThat(services).as("%s services", compose).containsKey(name);
        return (Map<String, Object>) services.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> networkNames(Map<String, Object> service) {
        Object networks = service.get("networks");
        if (networks instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.copyOf(((Map<String, Object>) networks).keySet());
    }

    // ---------------------------------------------------------------- C-3 拓扑

    @Test
    void orderArenaJoinsOnlyAlertNet() {
        Map<String, Object> arena = service(alertCompose, "order-arena");
        assertThat(networkNames(arena)).containsExactly("alert-net");
    }

    @Test
    void chaosAdminJoinsOnlyEvalMgmtWithNoHostPorts() {
        Map<String, Object> admin = service(alertCompose, "arena-chaos-admin");
        assertThat(networkNames(admin)).containsExactly("eval-mgmt");
        assertThat((Object) admin.get("ports")).as("管理面零宿主端口").isNull();
    }

    @Test
    void holmesgptDoesNotJoinEvalMgmt() {
        Map<String, Object> holmes = service(alertCompose, "holmesgpt");
        assertThat(networkNames(holmes)).doesNotContain("eval-mgmt");
    }

    @Test
    void evalMgmtIsExternalNetwork() {
        Map<String, Object> networks = (Map<String, Object>) alertCompose.get("networks");
        Map<String, Object> evalMgmt = (Map<String, Object>) networks.get("eval-mgmt");
        assertThat(evalMgmt).containsEntry("external", true);
        assertThat(evalMgmt).containsEntry("name", "eval-mgmt");
    }

    @Test
    void postgresBridgesAlertNetAndEvalMgmt() {
        Map<String, Object> postgres = service(mainCompose, "postgres");
        assertThat(networkNames(postgres)).contains("internal", "alert-net", "eval-mgmt");
    }

    // ---------------------------------------------------------------- 安全项 + 资源限额

    @Test
    void orderArenaHardeningAndMemoryBudget() {
        Map<String, Object> arena = service(alertCompose, "order-arena");
        assertThat(arena.get("mem_limit")).as("M2-01 冻结 512MiB 限额").isEqualTo("512m");
        assertThat(arena.get("read_only")).isEqualTo(true);
        assertThat((List<String>) arena.get("cap_drop")).contains("ALL");
        assertThat((List<String>) arena.get("security_opt")).contains("no-new-privileges:true");
        assertThat((Object) arena.get("healthcheck")).as("容器 health 依赖 healthcheck").isNotNull();
        assertThat((List<String>) arena.get("ports"))
                .as("业务 API 仅绑 loopback（BIND 插值默认值必须是 127.0.0.1）")
                .allSatisfy(p -> {
                    assertThat(p).contains("127.0.0.1");
                    assertThat(p).doesNotContain("0.0.0.0");
                });
    }

    @Test
    void chaosAdminHardening() {
        Map<String, Object> admin = service(alertCompose, "arena-chaos-admin");
        assertThat(admin.get("read_only")).isEqualTo(true);
        assertThat((List<String>) admin.get("cap_drop")).contains("ALL");
        assertThat((List<String>) admin.get("security_opt")).contains("no-new-privileges:true");
        assertThat((Object) admin.get("healthcheck")).isNotNull();
    }

    // ---------------------------------------------------------------- 迁移单一事实源

    @Test
    void arenaMigrateUsesOrderArenaMigrationDirAsOwner() {
        Map<String, Object> migrate = service(alertCompose, "arena-migrate");
        assertThat(migrate.get("restart")).isEqualTo("no");
        Map<String, Object> environment = (Map<String, Object>) migrate.get("environment");
        assertThat(environment).containsEntry("FLYWAY_USER", "postgres");
        assertThat(environment).containsEntry("FLYWAY_SCHEMAS", "arena");
        List<String> volumes = (List<String>) migrate.get("volumes");
        assertThat(volumes).anySatisfy(v -> assertThat(v)
                // compose 位于 deploy/alert/，仓库根需再上一级（195 部署实证：少一级挂成空目录）
                .startsWith("../../order-arena/src/main/resources/db/migration")
                .endsWith(":ro"));
    }

    @Test
    void arenaImageBuildContextsPointAtModules() {
        Map<String, Object> arena = service(alertCompose, "order-arena");
        Map<String, Object> admin = service(alertCompose, "arena-chaos-admin");
        assertThat((Map<String, Object>) arena.get("build")).containsEntry("context", "../../order-arena");
        assertThat((Map<String, Object>) admin.get("build")).containsEntry("context", "../../arena-chaos-admin");
    }

    // ---------------------------------------------------------------- 存量告警面保留

    @Test
    void existingAlertStackServicesPreserved() {
        for (String name : new String[]{"prometheus", "alertmanager", "holmesgpt"}) {
            assertThat(service(alertCompose, name)).isNotNull();
        }
    }
}
