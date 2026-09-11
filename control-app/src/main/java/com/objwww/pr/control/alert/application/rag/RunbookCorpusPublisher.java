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
 * EN-07 固定语料库发布面：N 份 runbook 文档（RUNBOOK_DOC）+ 1 份目录快照
 * （RUNBOOK_CATALOG）一次性发布，返回目录 digest——即 RAG 面的固定语料身份
 * （R07：装配面 digest 一旦 pin，Run 中不换；新语料发布走新快照）。
 *
 * <p>身份 = 内容 canonical digest（ReleaseAsset 内容寻址）：同语料重发幂等零新行、
 * 一字之差即新目录身份（篡改=新身份的 S10 面）。发布面不做有效期裁剪——窗口
 * 原样入目录，排除语义归读面/执行器（R03）。
 *
 * <p>L0：release 域端口依赖，零框架。
 */
public class RunbookCorpusPublisher {

    /** 发布输入：窗口 null = 无界；tags 供目录过滤面 */
    public record DocInput(String runbookId, String title, String description, String text,
            List<String> tags, Instant effectiveFrom, Instant effectiveUntil) {
        public DocInput {
            Objects.requireNonNull(runbookId, "runbookId 不得为 null");
            Objects.requireNonNull(text, "text 不得为 null");
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    private final ReleaseAssetRepository assets;

    public RunbookCorpusPublisher(ReleaseAssetRepository assets) {
        this.assets = Objects.requireNonNull(assets, "assets 不得为 null");
    }

    /** 发布整卷语料，返回目录资产 digest（固定语料身份，装配面 pin 锚） */
    public Digest publish(List<DocInput> docs, String createdBy, Instant now) {
        Objects.requireNonNull(docs, "docs 不得为 null");
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("语料至少一份文档（空目录拒发）");
        }
        List<Map<String, Object>> catalogEntries = new ArrayList<>();
        for (DocInput doc : docs) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("runbook_id", doc.runbookId());
            content.put("title", doc.title());
            content.put("description", doc.description());
            content.put("text", doc.text());
            content.put("tags", doc.tags());
            if (doc.effectiveFrom() != null) {
                content.put("effective_from", doc.effectiveFrom().toString());
            }
            if (doc.effectiveUntil() != null) {
                content.put("effective_until", doc.effectiveUntil().toString());
            }
            ReleaseAsset document = ReleaseAsset.of(ReleaseAsset.KIND_RUNBOOK_DOC,
                    content, createdBy, now);
            assets.insert(document);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("runbook_id", doc.runbookId());
            entry.put("title", doc.title());
            entry.put("description", doc.description());
            entry.put("tags", doc.tags());
            entry.put("doc_digest", document.assetDigest().hex());
            if (doc.effectiveFrom() != null) {
                entry.put("effective_from", doc.effectiveFrom().toString());
            }
            if (doc.effectiveUntil() != null) {
                entry.put("effective_until", doc.effectiveUntil().toString());
            }
            catalogEntries.add(entry);
        }
        ReleaseAsset catalog = ReleaseAsset.of(ReleaseAsset.KIND_RUNBOOK_CATALOG,
                Map.of("schema_version", "1", "documents", catalogEntries),
                createdBy, now);
        assets.insert(catalog);
        return catalog.assetDigest();
    }
}
