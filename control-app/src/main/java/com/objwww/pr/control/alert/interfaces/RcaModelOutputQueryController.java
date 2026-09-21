package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.agent.RcaModelOutputReadPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 模型输入/输出捕获读面（V167，/api/rca-runs/** 已由 SecurityConfig 归 OPERATOR）：
 * run 级按 action_seq 返回每步调用的账本锚 + 输入（V90）/输出（V167）两侧捕获，
 * 供 RunDetailView 模型调用时间线行展开消费（A/B 对照实验逐步推理可展示）。
 * docker profile（PersistenceConfig 同域，JevSelectionQueryController 同律）。
 * 捕获未落行的一侧（输出 OFF 档/调用未成功、输入捕获缺失）→ 该侧 null 如实，
 * 前端显"无捕获行"，不造空文本假象；掩敏行带 redactionNote，前端标"已掩敏"。
 */
@RestController
@org.springframework.context.annotation.Profile("docker")
@RequestMapping("/api/rca-runs/{runId}/model-outputs")
public class RcaModelOutputQueryController {

    private static final int MAX_ROWS = 200;

    private final RcaModelOutputReadPort reads;

    public RcaModelOutputQueryController(RcaModelOutputReadPort reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @GetMapping
    public Map<String, Object> byRun(@PathVariable String runId) {
        UUID id;
        try {
            id = UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return Map.of("error", "runId 非法");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RcaModelOutputReadPort.StepRow row : reads.byRun(id, MAX_ROWS)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelCallId", row.modelCallId().toString());
            item.put("actionSeq", row.actionSeq());
            item.put("roleId", row.roleId());
            item.put("state", row.state());
            item.put("input", side(row.input()));
            item.put("output", side(row.output()));
            rows.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        return out;
    }

    /** 无捕获行 → null 如实；DIGEST_ONLY 档 text=null + digest 在场（可对账不可读文） */
    private static Map<String, Object> side(RcaModelOutputReadPort.CaptureSide side) {
        if (side == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", side.level());
        m.put("text", side.text());
        m.put("digest", side.digest());
        m.put("messageBytes", side.messageBytes());
        m.put("redactionNote", side.redactionNote());
        return m;
    }
}
