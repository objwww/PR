package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.RunConfigSwitchService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * EN-10 切换状态查询面（O07"显示命令真实状态/epoch，不能提交即 toast 已生效"）：
 * EN-04 epoch 历史与 mixed-config 判定的只读 REST 投影——数据本体是
 * {@link RunConfigSwitchService#history}/{@code mixedConfig}（EN-04 已验收的领域
 * 查询，本卡仅暴露 REST）；权限沿既有 /api/rca-runs/** OPERATOR 矩阵。
 *
 * <p>空史（存量 Run/绑定期留白）= 200 空数组，不 404 不伪造——页面空态如实。
 */
@RestController
@Profile("docker")
public class RunConfigEpochController {

    private final RunConfigSwitchService service;

    public RunConfigEpochController(RunConfigSwitchService service) {
        this.service = Objects.requireNonNull(service, "service 不得为 null");
    }

    /** Run 配置代际历史：epochs（追加事实，含准入播种行）+ mixed_config（混代判定） */
    @GetMapping("/api/rca-runs/{runId}/config-epochs")
    public ResponseEntity<Map<String, Object>> configEpochs(
            @PathVariable("runId") UUID runId) {
        List<Map<String, Object>> epochs = new ArrayList<>();
        for (var row : service.history(runId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("config_epoch", row.configEpoch());
            item.put("release_digest", row.releaseDigest());
            item.put("source_command_id", row.sourceCommandId() == null ? null
                    : row.sourceCommandId().toString());
            item.put("applied_by", row.appliedBy());
            item.put("reason", row.reason());
            item.put("created_at", row.createdAt().toString());
            epochs.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId.toString());
        body.put("mixed_config", service.mixedConfig(runId));
        body.put("epochs", epochs);
        return ResponseEntity.ok(body);
    }
}
