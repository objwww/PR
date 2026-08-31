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
 * <p>定位算法（确定性，三级，**全部要求唯一命中**）：
 * <ol>
 *   <li>片段整体在文件内容中精确子串匹配；</li>
 *   <li>退化为行级匹配：片段与文件行各自 trim 后做连续子序列匹配（容忍模型回显时的缩进漂移）；</li>
 *   <li>INC-19：片段所有非空行带同一种 diff 行前缀（{@code +}/{@code -}/统一前导单空格）时，
 *       整体剥离一个字符后重走前两级（混合前缀拒绝剥离，防误伤正常缩进）。</li>
 * </ol>
 * INC-29：任一级命中次数大于一 = 锚定歧义，该 finding 丢弃并计 dropped——
 * "取第一个命中"等价于猜行号，违反"不信模型行号"原则。
 *
 * <p>文件路径归一化（INC-19）：模型回写的 file 先精确查找，未命中再剥 diff 风格
 * {@code a/}/{@code b/} 前缀与前导 {@code /}；落 finding 的路径与 fingerprint 一律用
 * 快照内的规范路径（GitHub review 评论也要求仓库相对路径）。
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
            String path = resolvePath(fileContents, mf.file());
            String content = path == null ? null : fileContents.get(path);
            if (content == null) {
                dropped++; // 模型幻觉出的文件路径
                continue;
            }
            int[] range = locate(content, mf.existingCode());
            if (range == null) {
                dropped++; // 片段找不到：丢弃并计数（UT-05）
                continue;
            }
            Digest fingerprint = fingerprint(headSha, path, range[0], range[1],
                    mf.rule(), mf.message());
            drafts.add(new ReviewFindingDraft(path, range[0], range[1],
                    mf.rule(), mf.severity(), mf.message(), fingerprint));
        }
        return new MappingResult(drafts, dropped);
    }

    /**
     * 模型回写路径 → 快照规范路径（INC-19）：精确命中优先；否则剥前导 "/" 与
     * diff 风格 {@code a/}/{@code b/} 前缀再试一次；仍不命中返回 null（计 dropped）。
     */
    static String resolvePath(Map<String, String> fileContents, String file) {
        if (file == null) {
            return null;
        }
        if (fileContents.containsKey(file)) {
            return file;
        }
        String stripped = file;
        if (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        if (stripped.startsWith("a/") || stripped.startsWith("b/")) {
            stripped = stripped.substring(2);
        }
        return !stripped.equals(file) && fileContents.containsKey(stripped) ? stripped : null;
    }

    /**
     * 在文件内容中定位片段，返回 1-based [lineStart, lineEnd]；定位失败返回 null。
     * 模型自报行号不参与定位（UT-05：模型报错行号由工程代码纠正）。
     */
    static int[] locate(String content, String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null; // 无定位锚点
        }
        int[] range = locateRaw(content, snippet);
        if (range != null) {
            return range;
        }
        // 3) INC-19：模型把 diff 行前缀抄进了片段 → 同种前缀整体剥离后重试
        String stripped = stripDiffLinePrefix(snippet);
        return stripped == null ? null : locateRaw(content, stripped);
    }

    /** 前两级：精确子串 → 行级 trim 连续子序列；两级都要求**唯一命中** */
    private static int[] locateRaw(String content, String snippet) {
        // 1) 精确子串匹配
        int idx = content.indexOf(snippet);
        if (idx >= 0) {
            // INC-29：锚点必须唯一。片段出现多次时"取第一个命中"等价于猜行号——
            // 与"不信模型自报行号"的原则同样违反。歧义 → 返回 null（丢弃并计 dropped），
            // 宁可少报一条 finding，不可把评论钉在错误的行上。
            if (content.indexOf(snippet, idx + 1) >= 0) {
                return null;
            }
            return new int[]{lineOf(content, idx), lineOf(content, idx + snippet.length())};
        }
        // 2) 行级 trim 匹配（容忍缩进/行尾漂移）；同样要求唯一命中
        String[] snippetLines = snippet.strip().split("\n", -1);
        String[] fileLines = content.split("\n", -1);
        if (snippetLines.length > fileLines.length) {
            return null;
        }
        int[] found = null;
        outer:
        for (int start = 0; start + snippetLines.length <= fileLines.length; start++) {
            for (int j = 0; j < snippetLines.length; j++) {
                if (!fileLines[start + j].strip().equals(snippetLines[j].strip())) {
                    continue outer;
                }
            }
            if (found != null) {
                return null; // INC-29：第二次命中 = 歧义，丢弃
            }
            found = new int[]{start + 1, start + snippetLines.length};
        }
        return found;
    }

    /**
     * INC-19 diff 行前缀剥离：所有非空行带同一种前缀（{@code +}/{@code -}/单空格）时
     * 每行剥一个字符；任一行无前缀或前缀混种 → 返回 null 拒绝剥离（防误伤正常缩进代码）。
     */
    static String stripDiffLinePrefix(String snippet) {
        String[] lines = snippet.split("\n", -1);
        char prefix = 0;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue; // 空行无前缀可言，不参与判定
            }
            char c = line.charAt(0);
            if (c != '+' && c != '-' && c != ' ') {
                return null;
            }
            if (prefix == 0) {
                prefix = c;
            } else if (c != prefix) {
                return null; // 混合前缀：不是统一抄来的 diff 块，拒绝剥离
            }
        }
        if (prefix == 0) {
            return null; // 全空行
        }
        StringBuilder sb = new StringBuilder(snippet.length());
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                sb.append(lines[i].substring(1));
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
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
