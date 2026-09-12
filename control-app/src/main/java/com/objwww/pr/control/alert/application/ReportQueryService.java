package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader.RcaReportView;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告 tab 只读投影（GET /api/rca-runs/{runId}/report 的服务层）。
 *
 * <p>投影语义：
 * <ul>
 *   <li>run 无报告行 → {@code {"state":"NONE"}}（200 而非 404——前端三态区分用；
 *       run 存在性由控制器先验，不存在仍 404）；</li>
 *   <li>有报告 → 最新行（created_at 升序末行，id 决胜）投影：state=OK
 *       （{@code STRUCTURE_VALIDATED}）/ REJECTED（{@code REJECTED_*}）；REJECTED
 *       时 packageJson 仍透传（审计可见），validationErrors 逐条给出拒绝原因链；</li>
 *   <li>多报告（重试多行）→ 只投影最新行 + supersededCount=被取代行数（不逐条列）；</li>
 *   <li>packageJson 六段式原文透传：可解析 → JSON 树内联（前端直接渲染），不可解析
 *       （如 REJECTED_MALFORMED 的畸形原文）→ 原样字符串（前端按原文展示，不伪造结构）；</li>
 *   <li>publication = report_publication 发布记录（state/attemptCount/maxAttempts/
 *       updatedAt/lastError）；无发布记录 → null 如实（报告未进入外发链）；</li>
 *   <li><b>raw_text 不透出</b>：Holmes 原文非前端读面，读端口（{@link RcaReportReader}）
 *       结构上无此字段——本服务想透也拿不到。</li>
 * </ul>
 */
public class ReportQueryService {

    private final RcaReportReader reports;
    private final ReportPublicationRepository publications;
    private final ObjectMapper mapper;

    public ReportQueryService(RcaReportReader reports, ReportPublicationRepository publications,
                              ObjectMapper mapper) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 报告投影（调用方先验 run 存在；无报告 → state=NONE） */
    public Map<String, Object> report(UUID runId) {
        List<RcaReportView> rows = new ArrayList<>(reports.findByRunId(runId));
        if (rows.isEmpty()) {
            return Map.of("state", "NONE");
        }
        // 防御排序：最新行 = created_at 升序末行（id 决胜），不依赖实现方 ORDER BY
        rows.sort(Comparator.comparing(RcaReportView::createdAt).thenComparing(RcaReportView::id));
        RcaReportView latest = rows.get(rows.size() - 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", latest.validationStatus() == ValidationStatus.STRUCTURE_VALIDATED
                ? "OK" : "REJECTED");
        out.put("reportId", latest.id().toString());
        out.put("schemaVersion", latest.schemaVersion());
        out.put("validationStatus", latest.validationStatus().name());
        out.put("validationErrors", latest.validationErrors());
        out.put("model", latest.model());
        out.put("promptTokens", latest.promptTokens());
        out.put("completionTokens", latest.completionTokens());
        out.put("totalTokens", latest.totalTokens());
        out.put("usageMissing", latest.usageMissing());
        out.put("createdAt", latest.createdAt());
        out.put("packageJson", packageNode(latest.packageJson()));
        out.put("supersededCount", rows.size() - 1);
        out.put("publication", publicationOf(latest.id()));
        return out;
    }

    /** 六段式原文透传：可解析 → JsonNode 内联；畸形原文（REJECTED_MALFORMED 面）→ 原样字符串 */
    private Object packageNode(String packageJson) {
        if (packageJson == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(packageJson);
            return node == null ? packageJson : node;
        } catch (Exception e) {
            return packageJson;
        }
    }

    /** 发布记录如实投影；无行 → null（报告未进入外发链，不冒充 PENDING） */
    private Map<String, Object> publicationOf(UUID reportId) {
        return publications.findByReportId(reportId).map(p -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("state", p.state().name());
            m.put("attemptCount", p.attemptCount());
            m.put("maxAttempts", p.maxAttempts());
            m.put("updatedAt", p.updatedAt());
            m.put("lastError", p.lastError());
            return m;
        }).orElse(null);
    }
}
