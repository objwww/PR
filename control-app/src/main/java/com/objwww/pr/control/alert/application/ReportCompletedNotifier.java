package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry;
import com.objwww.pr.control.alert.domain.model.ReportPublication;
import com.objwww.pr.control.alert.domain.repository.NotifyOutboxRepository;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告触发条件判定 + notify_outbox 生产者（AM3 §6.5；M3-08/19）。
 *
 * <p>触发点冻结：STRUCTURE_VALIDATED 即触发（EVIDENCE_VALIDATED 属 AM4），payload 必须携带
 * "AI 候选结论，未完成证据语义验证" 候选标记。调用方（RcaRunOrchestrator.finishTask）在
 * 报告落库的同一事务内调用——报告 + publication + outbox 原子（补漏 reconciler 二选一
 * 冻结：本期选同事务）。
 *
 * <p>payload 白名单：只放 summary/root_cause 三元组/impact/remediation 的有界摘录与
 * 审计标识——不携带报告正文、raw_text 或任何工具原文（渲染排版在 notify-app）。
 */
public class ReportCompletedNotifier {

    public static final String CANDIDATE_MARKER = "AI 候选结论，未完成证据语义验证";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReportPublicationRepository publications;
    private final NotifyOutboxRepository outbox;
    private final List<String> channels;
    private final String templateVersion;
    private final int maxExcerptChars;

    public ReportCompletedNotifier(ReportPublicationRepository publications,
                                   NotifyOutboxRepository outbox,
                                   List<String> channels,
                                   String templateVersion,
                                   int maxExcerptChars) {
        this.publications = Objects.requireNonNull(publications);
        this.outbox = Objects.requireNonNull(outbox);
        this.channels = List.copyOf(Objects.requireNonNull(channels));
        if (this.channels.isEmpty()) {
            throw new IllegalArgumentException("channels 不得为空（至少一个测试渠道）");
        }
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("templateVersion 不得为空");
        }
        this.templateVersion = templateVersion;
        if (maxExcerptChars < 1) {
            throw new IllegalArgumentException("maxExcerptChars 从 1 起");
        }
        this.maxExcerptChars = maxExcerptChars;
    }

    /**
     * 报告就绪 → publication(READY) + 每渠道一条 outbox（同事务）。
     * 同一 operation_id 贯穿全部渠道行——重复投递可检测可审计（INV-AM3-2）。
     */
    public void onReportValidated(UUID reportId, UUID runId, UUID attemptId,
                                  String summary, String rootCauseComponent,
                                  String rootCauseFaultType, String rootCauseReasonCode,
                                  String impact, String remediation, Instant now) {
        UUID publicationId = UUID.randomUUID();
        publications.insert(ReportPublication.ready(publicationId, reportId, now));
        UUID operationId = UUID.randomUUID();
        String payload = buildPayload(reportId, runId, attemptId, operationId,
                summary, rootCauseComponent, rootCauseFaultType, rootCauseReasonCode,
                impact, remediation);
        for (String channel : channels) {
            outbox.insert(NotifyOutboxEntry.pending(UUID.randomUUID(), publicationId,
                    reportId, channel, templateVersion, operationId, payload, now));
        }
    }

    private String buildPayload(UUID reportId, UUID runId, UUID attemptId, UUID operationId,
                                String summary, String rootCauseComponent,
                                String rootCauseFaultType, String rootCauseReasonCode,
                                String impact, String remediation) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("operation_id", operationId.toString());
        payload.put("report_id", reportId.toString());
        payload.put("run_id", runId.toString());
        payload.put("attempt_id", attemptId.toString());
        payload.put("candidate", true);
        payload.put("notice", CANDIDATE_MARKER);
        payload.put("summary", excerpt(summary));
        payload.put("impact", excerpt(impact));
        payload.put("remediation", excerpt(remediation));
        payload.put("root_cause_component", excerpt(rootCauseComponent));
        payload.put("root_cause_fault_type", excerpt(rootCauseFaultType));
        payload.put("root_cause_reason_code", excerpt(rootCauseReasonCode));
        return payload.toString();
    }

    private String excerpt(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxExcerptChars ? value
                : value.substring(0, maxExcerptChars) + "…";
    }
}
