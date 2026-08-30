package com.objwww.pr.control.domain.review;

import com.objwww.pr.shared.Digest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finding 工程映射（domain 纯逻辑，§3 FindingMapper）：
 * 不信任模型自报行号——用 {@code existing_code} 片段在对应文件内容里重新定位精确行号；
 * 片段找不到 → 丢弃该 finding 并计数（UT-05）。
 *
 * <p>定位算法（确定性，两级）：
 * <ol>
 *   <li>片段整体在文件内容中精确子串匹配；</li>
 *   <li>退化为行级匹配：片段与文件行各自 trim 后做连续子序列匹配（容忍模型回显时的缩进漂移）。</li>
 * </ol>
 *
 * <p>finding_fingerprint = SHA256(head_sha | file | line_range | rule | normalize(message))，
 * message 做空白归一化（trim + 连续空白折叠为单空格，UT-06）。
 */
public final class FindingMapper {

    /** 映射结果：成功映射的 finding 草稿 + 丢弃计数（幻觉文件/片段失配/缺锚点） */
    public record MappingResult(List<ReviewFindingDraft> findings, int droppedCount) {
        public MappingResult {
            findings = List.copyOf(Objects.requireNonNull(findings));
            if (droppedCount < 0) {
                throw new IllegalArgumentException("droppedCount 不能为负");
            }
        }
    }

    public MappingResult map(String headSha, Map<String, String> fileContents,
                             List<ModelFinding> modelFindings) {
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(fileContents, "fileContents");
        Objects.requireNonNull(modelFindings, "modelFindings");

        List<ReviewFindingDraft> drafts = new ArrayList<>();
        int dropped = 0;
        for (ModelFinding mf : modelFindings) {
            String content = mf.file() == null ? null : fileContents.get(mf.file());
            if (content == null) {
                dropped++; // 模型幻觉出的文件路径
                continue;
            }
            int[] range = locate(content, mf.existingCode());
            if (range == null) {
                dropped++; // 片段找不到：丢弃并计数（UT-05）
                continue;
            }
            Digest fingerprint = fingerprint(headSha, mf.file(), range[0], range[1],
                    mf.rule(), mf.message());
            drafts.add(new ReviewFindingDraft(mf.file(), range[0], range[1],
                    mf.rule(), mf.severity(), mf.message(), fingerprint));
        }
        return new MappingResult(drafts, dropped);
    }

    /**
     * 在文件内容中定位片段，返回 1-based [lineStart, lineEnd]；定位失败返回 null。
     * 模型自报行号不参与定位（UT-05：模型报错行号由工程代码纠正）。
     */
    static int[] locate(String content, String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null; // 无定位锚点
        }
        // 1) 精确子串匹配
        int idx = content.indexOf(snippet);
        if (idx >= 0) {
            return new int[]{lineOf(content, idx), lineOf(content, idx + snippet.length())};
        }
        // 2) 行级 trim 匹配（容忍缩进/行尾漂移）
        String[] snippetLines = snippet.strip().split("\n", -1);
        String[] fileLines = content.split("\n", -1);
        if (snippetLines.length > fileLines.length) {
            return null;
        }
        outer:
        for (int start = 0; start + snippetLines.length <= fileLines.length; start++) {
            for (int j = 0; j < snippetLines.length; j++) {
                if (!fileLines[start + j].strip().equals(snippetLines[j].strip())) {
                    continue outer;
                }
            }
            return new int[]{start + 1, start + snippetLines.length};
        }
        return null;
    }

    /** 字符偏移 → 1-based 行号（endExclusive 偏移落在片段后一个字符时算片段最后一行） */
    private static int lineOf(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** UT-06：fingerprint = SHA256(head_sha|file|line_range|rule|normalize(message)) */
    static Digest fingerprint(String headSha, String file, int lineStart, int lineEnd,
                              String rule, String message) {
        return Digest.sha256Of(String.join("|",
                headSha,
                file,
                lineStart + "-" + lineEnd,
                rule == null ? "" : rule,
                normalize(message)));
    }

    /** message 空白归一化：trim + 连续空白折叠为单空格（UT-06） */
    static String normalize(String message) {
        return message == null ? "" : message.strip().replaceAll("\\s+", " ");
    }
}
