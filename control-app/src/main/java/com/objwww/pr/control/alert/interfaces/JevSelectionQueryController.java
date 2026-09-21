package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.repository.RcaJevSelectionPort;
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
 * Jev 选材明细读面（JE-02，/api/rca-runs/** 已由 SecurityConfig 归 OPERATOR）：
 * run 级选材决策清单（池/保护/选中/被裁/逐候选概率/是否应用），供 RunDetailView
 * 「Jev 选材」页签消费。docker profile（PersistenceConfig 同域，EventQueryController
 * 同律）。未启用/无行 → 空表（前端显示"无选材记录"）。
 */
@RestController
@org.springframework.context.annotation.Profile("docker")
@RequestMapping("/api/rca-runs/{runId}/jev-selections")
public class JevSelectionQueryController {

    private static final int MAX_ROWS = 50;

    private final RcaJevSelectionPort selections;

    public JevSelectionQueryController(RcaJevSelectionPort selections) {
        this.selections = Objects.requireNonNull(selections, "selections");
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
        for (com.objwww.pr.control.alert.domain.agent.RcaJevSelection row :
                selections.findByRun(id, MAX_ROWS)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id().toString());
            item.put("taskId", row.taskId().toString());
            item.put("mode", row.mode());
            item.put("applied", row.applied());
            item.put("poolRefs", row.poolRefs());
            item.put("protectedRefs", row.protectedRefs());
            item.put("selectedRefs", row.selectedRefs());
            item.put("omittedRefs", row.omittedRefs());
            item.put("probabilities", row.probabilities());
            item.put("modelCallId", row.modelCallId() == null
                    ? null : row.modelCallId().toString());
            item.put("latencyMs", row.latencyMs());
            item.put("totalTokens", row.totalTokens());
            item.put("createdAt", row.createdAt() == null
                    ? null : row.createdAt().toString());
            rows.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        return out;
    }
}
