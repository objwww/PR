package com.objwww.pr.control.alert.application.rag;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EN-07 固定语料库读面（§三阶段 1，HolmesGPT 轻模式）：runbook 语料复用 release_asset
 * （V60，§8.2"优先复用现有表"）——目录资产（RUNBOOK_CATALOG）按 (kind, catalog digest)
 * 精确解析，文档资产（RUNBOOK_DOC）按目录登记 doc_digest 精确取文。
 *
 * <p>四条红线钉在本类：
 * <ul>
 *   <li>R12 完整性：digest 寻址结构上没有"latest"可回退——目录缺席 =
 *       {@link CorpusUnavailableException}（R02 与"无匹配"分码），文档缺席/条目身份
 *       不符 = {@link CorpusIntegrityException}（明确不可验证）；</li>
 *   <li>R07 固定快照：Run 构造期 pin catalog digest，snapshot 为不可变值对象——
 *       新发布换 digest 不换旧引用，新 Run 读新快照；</li>
 *   <li>R03 有效期窗：activeEntries 排除过期/未生效条目（全量条目仍在 snapshot 中
 *       供展示排除原因）；</li>
 *   <li>RAG 结果只进 Findings 参考区：本类零 LLM、零策略语义，正文原样上交
 *       （"仅参考"标记由执行器渲染面携带）。</li>
 * </ul>
 *
 * <p>L0：release 域端口依赖，零框架（ControlArchitectureTest 同律）。
 */
public class RunbookCorpusStore {

    /** 目录条目（R01 版本面：docDigest 即文档资产身份；窗口 null = 无界） */
    public record CatalogEntry(String runbookId, String title, String description,
            List<String> tags, String docDigest, Instant effectiveFrom,
            Instant effectiveUntil) {
    }

    /** 语料快照（不可变值对象：R07 Run 内固定绑定的持有面；documents = 装载即验的
     * 整卷文档，键 = doc_digest） */
    public record CorpusSnapshot(Digest catalogDigest, List<CatalogEntry> entries,
            Map<String, RunbookDocument> documents) {
        public CorpusSnapshot {
            entries = List.copyOf(entries);
            documents = Map.copyOf(documents);
        }
    }

    /** 取文结果（正文 + 与目录登记一致的文档 digest） */
    public record RunbookDocument(String runbookId, String title, String description,
            String text, String docDigest) {
    }

    /** 目录资产缺席（R02：SOURCE_UNAVAILABLE 面，≠ 无匹配 ≠ 完整性失败） */
    public static final class CorpusUnavailableException extends RuntimeException {
        public CorpusUnavailableException(String message) {
            super(message);
        }
    }

    /** 语料完整性失败（R12：明确不可验证，不回退不补齐） */
    public static final class CorpusIntegrityException extends RuntimeException {
        public CorpusIntegrityException(String message) {
            super(message);
        }
    }

    private final ReleaseAssetRepository assets;

    public RunbookCorpusStore(ReleaseAssetRepository assets) {
        this.assets = Objects.requireNonNull(assets, "assets 不得为 null");
    }

    /** 按 catalog digest 精确解析目录快照——<b>装载即验整卷</b>（R12）：每个登记文档
     * 存在性与身份一致性当场校验并内嵌，缺失/不符 = CorpusIntegrityException，
     * 不产出"部分可用"的快照 */
    public CorpusSnapshot load(Digest catalogDigest) {
        ReleaseAsset catalog = assets.findByDigest(ReleaseAsset.KIND_RUNBOOK_CATALOG,
                        catalogDigest)
                .orElseThrow(() -> new CorpusUnavailableException(
                        "SOURCE_UNAVAILABLE: 语料目录资产不存在（digest=" + catalogDigest.hex()
                                + "），不猜不补"));
        Object documents = catalog.content().get("documents");
        if (!(documents instanceof List<?> list)) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 目录资产缺 documents 面，明确不可验证: " + catalogDigest.hex());
        }
        List<CatalogEntry> entries = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> entry) {
                entries.add(entryOf(entry));
            }
        }
        Map<String, RunbookDocument> loaded = new LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            RunbookDocument document = resolveDocument(entry);
            loaded.put(document.docDigest(), document);
        }
        return new CorpusSnapshot(catalogDigest, entries, loaded);
    }

    /** 按目录登记 digest 取文（R01 版本面）：只从装载即验的快照内嵌面出——
     * 快照内查无 = 目录条目与装载文档不符（R12 明确不可验证） */
    public RunbookDocument document(CorpusSnapshot snapshot, CatalogEntry entry) {
        RunbookDocument document = snapshot.documents().get(entry.docDigest());
        if (document == null) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 登记文档不在已验快照内（runbook_id=" + entry.runbookId()
                            + "），明确不可验证，不回退补齐");
        }
        if (!entry.runbookId().equals(document.runbookId())) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 目录条目与文档身份不符（目录=" + entry.runbookId()
                            + " 实际=" + document.runbookId() + "），明确不可验证");
        }
        return document;
    }

    /** 窗内条目：effectiveFrom ≤ at < effectiveUntil（null = 无界；R03 排除面） */
    public List<CatalogEntry> activeEntries(CorpusSnapshot snapshot, Instant at) {
        List<CatalogEntry> active = new ArrayList<>();
        for (CatalogEntry entry : snapshot.entries()) {
            if ((entry.effectiveFrom() == null || !at.isBefore(entry.effectiveFrom()))
                    && (entry.effectiveUntil() == null || at.isBefore(entry.effectiveUntil()))) {
                active.add(entry);
            }
        }
        return active;
    }

    // ------------------------------------------------------------------ 内部

    /** 单条登记的文档解析 + 身份校验（装载期 R12 闸） */
    private RunbookDocument resolveDocument(CatalogEntry entry) {
        Digest docDigest;
        try {
            docDigest = new Digest(entry.docDigest());
        } catch (IllegalArgumentException e) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 目录登记 doc_digest 非法（runbook_id=" + entry.runbookId() + "）");
        }
        ReleaseAsset document = assets.findByDigest(ReleaseAsset.KIND_RUNBOOK_DOC, docDigest)
                .orElseThrow(() -> new CorpusIntegrityException(
                        "INTEGRITY: 登记文档资产缺失（runbook_id=" + entry.runbookId()
                                + " digest=" + docDigest.hex() + "），明确不可验证，不回退补齐"));
        Object runbookId = document.content().get("runbook_id");
        if (!entry.runbookId().equals(runbookId)) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 目录条目与文档身份不符（目录=" + entry.runbookId()
                            + " 实际=" + runbookId + "），明确不可验证");
        }
        return new RunbookDocument(entry.runbookId(),
                text(document.content().get("title")),
                text(document.content().get("description")),
                text(document.content().get("text")),
                docDigest.hex());
    }

    private static CatalogEntry entryOf(Map<?, ?> entry) {
        String runbookId = text(entry.get("runbook_id"));
        String docDigest = text(entry.get("doc_digest"));
        if (runbookId == null || docDigest == null) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 目录条目缺 runbook_id/doc_digest，明确不可验证");
        }
        return new CatalogEntry(runbookId,
                text(entry.get("title")),
                text(entry.get("description")),
                tags(entry.get("tags")),
                docDigest,
                instant(entry.get("effective_from")),
                instant(entry.get("effective_until")));
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static List<String> tags(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                tags.add(s);
            }
        }
        return List.copyOf(tags);
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            throw new CorpusIntegrityException(
                    "INTEGRITY: 目录条目时间窗非 ISO-8601 Instant: " + s);
        }
    }

    /** 目录条目渲染辅助（执行器展示面复用；LinkedHashMap 保持字段序） */
    public static Map<String, Object> renderEntry(CatalogEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runbook_id", entry.runbookId());
        view.put("title", entry.title());
        view.put("description", entry.description());
        view.put("tags", entry.tags());
        view.put("doc_digest", entry.docDigest());
        view.put("effective_from", entry.effectiveFrom() == null ? null
                : entry.effectiveFrom().toString());
        view.put("effective_until", entry.effectiveUntil() == null ? null
                : entry.effectiveUntil().toString());
        return view;
    }
}
