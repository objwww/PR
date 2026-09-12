package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.infrastructure.tool.CodeSourceBinding.Entry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R7-X10 code.search 执行器（部署版本代码取证，R7 方案 §二 L64/L209）：按 service 取
 * 宿主绑定仓库（模型不可指定仓库/commit/绝对路径），已绑定源码树内大小写不敏感的
 * 字面量行匹配——正则面不开放（ReDoS 与灾难回溯不进取证路径）。
 *
 * <p>界限：跳过依赖/构建产物/隐藏目录；单文件/累计扫描字节与文件数三重上限，超限
 * truncated=true 如实留痕；二进制文件（NUL 探针）跳过；命中行过秘密词形脱敏
 * （口径对齐 EventPayloadSanitizer.SECRET_LIKE）。零匹配 NO_DATA（空结果如实呈现）；
 * 结果恒带 binding 溯源块（host-declared 如实标注）与参考区 note。
 */
public class CodeSearchExecutor implements ToolExecutor {

    /** 命中行上限 / 扫描文件数上限 / 单文件字节上限 / 累计扫描字节上限 */
    static final int MAX_MATCHES = 20;
    static final int MAX_FILES_SCANNED = 2000;
    static final int MAX_FILE_BYTES = 512 * 1024;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    /** 单行截断（与 ContextAssembler.ITEM_LIMIT 同量级） */
    static final int LINE_CLIP = 200;

    /** 秘密词形脱敏（口径对齐 EventPayloadSanitizer.SECRET_LIKE 同一词表） */
    private static final java.util.regex.Pattern SECRET_LIKE = java.util.regex.Pattern.compile(
            "(sk-[A-Za-z0-9_-]{8,}|Bearer\\s+[A-Za-z0-9._-]{8,}|"
                    + "(?i)(api[_-]?key|secret|token|password)[\"'\\s:=:]{1,4}[A-Za-z0-9._-]{8,})");
    private static final String REDACTED = "[REDACTED]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path checkoutRoot;
    private final Map<String, Entry> binding;

    public CodeSearchExecutor(Path checkoutRoot, Map<String, Entry> binding) {
        if (!Files.isDirectory(checkoutRoot)) {
            throw new IllegalArgumentException("checkout 根不存在: " + checkoutRoot);
        }
        this.checkoutRoot = checkoutRoot;
        this.binding = Map.copyOf(binding);
    }

    @Override
    public byte[] execute(ToolExecutor.ToolExecution execution) {
        ParsedQuery query = parseArgs(execution.validatedArgs(), binding.keySet());
        Entry entry = binding.get(query.service());
        if (!CodeSourceBinding.repoDirExists(checkoutRoot, entry)) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "SOURCE_UNAVAILABLE: 绑定仓库源树缺席（宿主 checkout 面异常已脱敏）");
        }
        Scan scan = scan(checkoutRoot.resolve(entry.repo()), query);
        if (scan.matches().isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 绑定源树内无该字面量命中（空结果如实呈现）");
        }
        return render(entry, scan, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 语义面（静态 = L0 可测）

    record ParsedQuery(String service, String query, String pathPrefix) {
    }

    /** 参数语义面：service 必在绑定集（越权服务在触盘前拒）；path_prefix 过沙箱形状 */
    static ParsedQuery parseArgs(Map<String, Object> args, Set<String> allowedServices) {
        String service = text(args.get("service"));
        if (service == null || !allowedServices.contains(service)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 必在宿主绑定集内（未授权仓库即拒）");
        }
        String query = text(args.get("query"));
        if (query == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: query 必填");
        }
        String prefix = text(args.get("path_prefix"));
        if (prefix != null && (prefix.startsWith("/") || prefix.contains("\\")
                || prefix.contains(".."))) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: path_prefix 必为源树内相对路径（禁 .. 与绝对路径）");
        }
        return new ParsedQuery(service, query, prefix);
    }

    /** 命中行判定：大小写不敏感字面量包含 */
    static boolean matchesLine(String line, String lowerQuery) {
        return line.toLowerCase().contains(lowerQuery);
    }

    record Match(String path, long line, String text) {
    }

    record Scan(List<Match> matches, boolean truncated) {
    }

    /** 确定性扫描：路径字典序 + 行号升序；三重上限，触限即停并如实置 truncated */
    static Scan scan(Path repoRoot, ParsedQuery query) {
        String lowerQuery = query.query().toLowerCase();
        String prefix = query.pathPrefix() == null ? null
                : query.pathPrefix().endsWith("/") ? query.pathPrefix()
                        : query.pathPrefix() + "/";
        // 两段式：先收集 prefix 过滤后的候选路径（目录流序无字典序保证，大树触限
        // 顺序不确定——195 真树 1.2 万文件实证），排序后顺序扫描 = 确定性结果
        List<Path> candidates = new ArrayList<>();
        List<String> relativePaths = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(repoRoot, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    return CodeSourceBinding.SKIP_DIRS.contains(
                            dir.getFileName().toString())
                            || dir.getFileName().toString().startsWith(".")
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    String relative = repoRoot.relativize(file).toString()
                            .replace('\\', '/');
                    if (prefix != null && !relative.startsWith(prefix)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    if (candidates.size() >= MAX_FILES_SCANNED) {
                        truncated[0] = true;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    candidates.add(file);
                    relativePaths.add(relative);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "SOURCE_UNAVAILABLE: 源树扫描不可用（数据面异常已脱敏）");
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> relativePaths.get(a).compareTo(relativePaths.get(b)));
        List<Match> matches = new ArrayList<>();
        long bytesScanned = 0;
        for (int index : order) {
            Path file = candidates.get(index);
            if (matches.size() >= MAX_MATCHES) {
                truncated[0] = true;
                break;
            }
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                bytesScanned += bytes.length;
                if (bytesScanned > MAX_TOTAL_BYTES) {
                    truncated[0] = true;
                    break;
                }
                if (isBinary(bytes)) {
                    continue;
                }
                String[] lines = new String(bytes, StandardCharsets.UTF_8)
                        .split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (matchesLine(lines[i], lowerQuery)) {
                        matches.add(new Match(relativePaths.get(index), i + 1L,
                                redact(clip(lines[i]))));
                        if (matches.size() >= MAX_MATCHES) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                continue;
            }
        }
        if (matches.size() >= MAX_MATCHES) {
            truncated[0] = true;
        }
        return new Scan(List.copyOf(matches), truncated[0]);
    }

    /** 二进制探针：头部 8KB 出现 NUL 即非文本（取证面只走文本） */
    static boolean isBinary(byte[] bytes) {
        int probe = Math.min(bytes.length, 8192);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    static String clip(String line) {
        return line.length() <= LINE_CLIP ? line : line.substring(0, LINE_CLIP - 1) + "…";
    }

    static String redact(String line) {
        return SECRET_LIKE.matcher(line).replaceAll(REDACTED);
    }

    private byte[] render(Entry entry, Scan scan, long limitBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1_024);
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeBooleanField("truncated", scan.truncated());
            gen.writeFieldName("binding");
            writeBinding(gen, entry);
            gen.writeFieldName("result");
            gen.writeStartArray();
            for (Match match : scan.matches()) {
                gen.writeStartObject();
                gen.writeStringField("path", match.path());
                gen.writeNumberField("line", match.line());
                gen.writeStringField("text", match.text());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeStringField("note", "部署版本代码取证（只读；宿主声明绑定，第一期无"
                    + "运行时镜像 digest 核验）。行内容已过秘密词形脱敏；代码存在性可作"
                    + "证据，代码行为仍需结合运行时证据判断。");
            gen.writeEndObject();
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("code.search 结果序列化失败", e);
        }
        if (out.size() > Math.max(1, limitBytes)) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限");
        }
        return out.toByteArray();
    }

    static void writeBinding(JsonGenerator gen, Entry entry) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("repo", entry.repo());
        gen.writeStringField("commit", entry.commit());
        gen.writeStringField("provenance", "host-declared");
        gen.writeEndObject();
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.strip();
    }
}
