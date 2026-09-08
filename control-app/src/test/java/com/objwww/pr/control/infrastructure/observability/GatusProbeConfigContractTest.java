package com.objwww.pr.control.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-17 Gatus 外部探针静态契约门（deploy/gatus/ 配置面；2C4G 独立故障域，预算 96MiB）。
 *
 * <p>红线条：Gatus 是防自噬的<b>外部腿</b>（调研 §7）——告警走独立渠道直达值班接收器，
 * **永不进本系统 Incident 管线**（配置不得出现 control-app webhook 入口）；
 * 探针端点/告警阈值/资源上限/env-only 凭据在此钉死；真栈证据 = E2E-AM5-08
 * "停 195 control endpoint → 2C4G Gatus 独立探测阈值内告警与恢复"（部署段，IT 不计证据）。
 */
class GatusProbeConfigContractTest {

    private static String normalized(Path path) throws IOException {
        return String.join(" ", Files.readString(path).toLowerCase().split("\\s+"));
    }

    /** compose 服务块抽取：从 "  <name>:" 行到下一个顶层服务/段 */
    private static String serviceBlock(String compose, String service) {
        List<String> lines = new ArrayList<>();
        boolean in = false;
        for (String line : compose.split("\n")) {
            if (line.matches("^  \\S.*")) {
                in = line.startsWith("  " + service + ":");
            }
            if (in) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private static final Path GATUS_CONFIG =
            Path.of("../deploy/gatus/gatus-config.yml");
    private static final Path GATUS_COMPOSE =
            Path.of("../deploy/gatus/compose.gatus.yml");
    private static final Path GATUS_ENV =
            Path.of("../deploy/gatus/.env.example");

    @Test
    void gatusConfigPinsBlackboxProbesIndependentAlertingAndEnvOnlyChannel() throws IOException {
        String c = normalized(GATUS_CONFIG);

        // 黑盒 health 探针：控制面两腿 + 业务 canary（合成 canary 告警），阈值内告警
        assertThat(c).contains("name: rca_system_control_health");
        assertThat(c).contains("name: rca_system_alertmanager_health");
        assertThat(c).contains("name: business_canary_arena_frontend");
        assertThat(c).contains("[status] == 200");
        assertThat(c).contains("interval: 30s");

        // 独立值班通道：webhook 直投（env-only，零明文），恢复通知同发
        assertThat(c).contains("alerting:");
        assertThat(c).contains("url: ${gatus_oncall_webhook_url}");
        assertThat(c).contains("send-on-resolved: true");
        assertThat(c).contains("failure-threshold: 3");

        // 文件存储：探针状态跨重启可追（2C4G 本地卷）
        assertThat(c).contains("storage:");
        assertThat(c).contains("type: file");
    }

    @Test
    void gatusConfigNeverFeedsTheIncidentPipeline() throws IOException {
        String c = normalized(GATUS_CONFIG);

        // 防自噬外部腿红线：Gatus 永不进本系统 Incident 管线（M5-16 入口/值班通道不回环）
        assertThat(c).doesNotContain("/webhooks/alertmanager");
        assertThat(c).doesNotContain("control-app:8080");
    }

    @Test
    void composePinsIndependentFailureDomainBudgetAndHardening() throws IOException {
        // 逐行解析前剥 \r：Windows 侧传输（git archive autocrlf）会把 CRLF 落到 Linux 树，
        // Java 正则 . 不吃 \r，服务块抽取将整体失明（195 真机红证据 2026-09-08）
        String compose = Files.readString(GATUS_COMPOSE).replace("\r", "");
        String block = serviceBlock(compose, "gatus");

        // 独立故障域：无 depends_on（195 挂不牵连探针，探针挂不牵连 195）
        assertThat(block).doesNotContain("depends_on");

        // 96MiB 预算（架构 §内存表）+ 加固面与 control-app 同构
        assertThat(block.toLowerCase()).contains("memory: 96m");
        assertThat(block.toLowerCase()).contains("read_only: true");
        assertThat(block.toLowerCase()).contains("cap_drop");
        assertThat(block.toLowerCase()).contains("no-new-privileges");

        // 配置只读挂载 + 凭据 env 注入（零明文）
        assertThat(block.toLowerCase()).contains(":ro");
        assertThat(block.toLowerCase()).contains("gatus_oncall_webhook_url");

        // 端口仅 loopback（若发布 UI 面）
        for (String line : block.split("\n")) {
            if (line.trim().startsWith("- \"") && line.contains(":")) {
                assertThat(line.trim()).startsWith("- \"127.0.0.1:");
            }
        }
    }

    @Test
    void envExampleDocumentsTheDeploymentContractWithoutSecrets() throws IOException {
        String env = Files.readString(GATUS_ENV).toLowerCase();

        assertThat(env).contains("gatus_oncall_webhook_url");
        assertThat(env).contains("control_health_url");
        assertThat(env).contains("arena_health_url");
    }
}
