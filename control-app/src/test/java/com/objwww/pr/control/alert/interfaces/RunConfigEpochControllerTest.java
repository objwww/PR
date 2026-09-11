package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.RunConfigSwitchService;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository.EpochRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EN-10 切换状态查询面（O07：显示命令真实状态/epoch，"提交即生效"不可伪造）：
 * EN-04 epoch 历史与 mixed-config 判定的只读 REST 投影——数据本体是
 * RunConfigSwitchService.history/mixedConfig（EN-04 已验收），本面只做投影与
 * 空态诚实（空史 = 200 空数组，非 404）。
 */
class RunConfigEpochControllerTest {

    private static final UUID RUN = UUID.randomUUID();

    @Test
    @DisplayName("E01：history + mixed 投影——epoch/digest/command/applied_by 全字段可见")
    void projectsHistoryAndMixedFlag() {
        RunConfigSwitchService service = mock(RunConfigSwitchService.class);
        when(service.history(RUN)).thenReturn(List.of(
                new EpochRow(RUN, 0, "ab".repeat(32), null, "admission",
                        "准入播种（运行前固定）", Instant.parse("2026-09-11T00:00:00Z")),
                new EpochRow(RUN, 1, "cd".repeat(32), UUID.randomUUID(), "operator-alice",
                        "安全点切换", Instant.parse("2026-09-11T00:05:00Z"))));
        when(service.mixedConfig(RUN)).thenReturn(true);
        RunConfigEpochController controller = new RunConfigEpochController(service);

        ResponseEntity<Map<String, Object>> response = controller.configEpochs(RUN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("run_id", RUN.toString())
                .containsEntry("mixed_config", true);
        List<Map<String, Object>> epochs = castList(response.getBody().get("epochs"));
        assertThat(epochs).hasSize(2);
        assertThat(epochs.get(0)).containsEntry("config_epoch", 0L)
                .containsEntry("release_digest", "ab".repeat(32))
                .containsEntry("applied_by", "admission");
        assertThat(epochs.get(1)).containsEntry("config_epoch", 1L)
                .containsEntry("reason", "安全点切换");
        assertThat(epochs.get(0).get("source_command_id"))
                .as("来源命令行可空 = 准入播种，如实保留 null").isNull();
    }

    @Test
    @DisplayName("E02：空史（存量 Run/绑定期留白）→ 200 空数组 + mixed=false，不 404")
    void emptyHistoryIsHonest200() {
        RunConfigSwitchService service = mock(RunConfigSwitchService.class);
        when(service.history(RUN)).thenReturn(List.of());
        when(service.mixedConfig(RUN)).thenReturn(false);
        RunConfigEpochController controller = new RunConfigEpochController(service);

        ResponseEntity<Map<String, Object>> response = controller.configEpochs(RUN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("mixed_config", false);
        assertThat(castList(response.getBody().get("epochs"))).isEmpty();
    }

    @Test
    @DisplayName("E03：每调用恰一次服务查询（无缓存无旁路——状态恒从后端恢复，O06）")
    void delegatesExactlyOncePerCall() {
        RunConfigSwitchService service = mock(RunConfigSwitchService.class);
        when(service.history(RUN)).thenReturn(List.of());
        when(service.mixedConfig(RUN)).thenReturn(false);
        RunConfigEpochController controller = new RunConfigEpochController(service);

        controller.configEpochs(RUN);
        controller.configEpochs(RUN);

        Mockito.verify(service, Mockito.times(2)).history(RUN);
        Mockito.verify(service, Mockito.times(2)).mixedConfig(RUN);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object raw) {
        return (List<Map<String, Object>>) raw;
    }
}
