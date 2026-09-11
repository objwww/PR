package com.objwww.pr.control.infrastructure.rag;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusStore;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * EN-07 runbook_catalog_search 执行器（§三阶段 1）：渲染登记条目（id/title/description/
 * tags/有效期窗），匹配由模型按 description 语义面完成——本执行器只做子串/tag 确定性
 * 预过滤；条目状态 ACTIVE/EXPIRED/NOT_EFFECTIVE 随条目展示（R03：过期不隐藏、不作
 * 有效建议）。无匹配 → NO_DATA（R02：与 SOURCE_UNAVAILABLE 分码区分）；语料目录缺席
 * → SOURCE_UNAVAILABLE。列表只渲染元数据零正文（R05 最小披露，正文归 fetch）。
 */
public class RunbookCatalogSearchExecutor implements ToolExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunbookCorpusStore store;
    private final com.objwww.pr.shared.Digest corpusDigest;
    private final Clock clock;

    public RunbookCatalogSearchExecutor(RunbookCorpusStore store,
            com.objwww.pr.shared.Digest corpusDigest, Clock clock) {
        this.store = Objects.requireNonNull(store, "store 不得为 null");
        this.corpusDigest = Objects.requireNonNull(corpusDigest, "corpusDigest 不得为 null");
        this.clock = Objects.requireNonNull(clock, "clock 不得为 null");
    }

    @Override
    public byte[] execute(ToolExecution execution) {
        String match = optionalText(execution.validatedArgs().get("match"));
        String tag = optionalText(execution.validatedArgs().get("tag"));
        RunbookCorpusStore.CorpusSnapshot snapshot;
        try {
            snapshot = store.load(corpusDigest);
        } catch (RunbookCorpusStore.CorpusUnavailableException
                | RunbookCorpusStore.CorpusIntegrityException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    e.getMessage());
        }
        Instant now = clock.instant();
        java.util.List<RunbookCorpusStore.CatalogEntry> hits = snapshot.entries().stream()
                .filter(e -> match == null || matches(e, match))
                .filter(e -> tag == null || e.tags().contains(tag))
                .toList();
        if (hits.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 目录中无匹配登记 runbook（NO_MATCH；与检索服务不可用区分）");
        }
        return render(snapshot, hits, now, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 语义面（静态 = L0 可测）

    /** 子串预过滤：id/title/description 大小写不敏感（语义匹配留给模型） */
    static boolean matches(RunbookCorpusStore.CatalogEntry entry, String match) {
        String needle = match.toLowerCase(Locale.ROOT);
        return contains(entry.runbookId(), needle) || contains(entry.title(), needle)
                || contains(entry.description(), needle);
    }

    static String statusOf(RunbookCorpusStore.CatalogEntry entry, Instant now) {
        if (entry.effectiveUntil() != null && !now.isBefore(entry.effectiveUntil())) {
            return "EXPIRED";
        }
        if (entry.effectiveFrom() != null && now.isBefore(entry.effectiveFrom())) {
            return "NOT_EFFECTIVE";
        }
        return "ACTIVE";
    }

    private static byte[] render(RunbookCorpusStore.CorpusSnapshot snapshot,
            java.util.List<RunbookCorpusStore.CatalogEntry> hits, Instant now,
            long limitBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1_024);
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeStringField("corpus_digest", snapshot.catalogDigest().hex());
            gen.writeFieldName("result");
            gen.writeStartArray();
            for (RunbookCorpusStore.CatalogEntry entry : hits) {
                Map<String, Object> view = RunbookCorpusStore.renderEntry(entry);
                view.put("status", statusOf(entry, now));
                gen.writeObject(view);
                gen.flush();
                if (out.size() > Math.max(1, limitBytes)) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
                }
            }
            gen.writeEndArray();
            gen.writeStringField("note", "登记条目按 description 语义匹配；正文用 "
                    + "fetch_runbook 按登记 id 取用，内容仅作参考（不构成根因结论）");
            gen.writeEndObject();
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("runbook_catalog_search 结果序列化失败", e);
        }
        if (out.size() > Math.max(1, limitBytes)) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限");
        }
        return out.toByteArray();
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
