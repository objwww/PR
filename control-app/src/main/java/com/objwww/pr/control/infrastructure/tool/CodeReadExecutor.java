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
 * R7-X10 code.read 执行器（部署版本代码取证，R7 方案 §二 L64/L209）：按 service 取
 * 宿主绑定仓库内的相对路径文件——沙箱解析（{@link CodeSourceBinding#resolveFile}：
 * 禁绝对路径/反斜杠/{@code ..} 段/越界逃逸），行窗读取（≤{@value #MAX_LINES} 行/
 * 单行截断/总字节上限），秘密词形脱敏（口径对齐 EventPayloadSanitizer.SECRET_LIKE）。
 *
 * <p>缺席文件 NO_DATA（空结果如实呈现）；二进制文件（NUL 探针）无文本可读 NO_DATA；
 * 结果恒带 binding 溯源块（host-declared 如实标注）与行窗元数据（total_lines/
 * truncated），模型可按窗续读不被静默截断误导。
 */
public class CodeReadExecutor implements ToolExecutor {

    /** 行窗上限 / 单行截断 / 读取字节上限 */
    static final int MAX_LINES = 200;
    static final int LINE_CLIP = 200;
    static final long MAX_READ_BYTES = 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path checkoutRoot;
    private final Map<String, Entry> binding;

    public CodeReadExecutor(Path checkoutRoot, Map<String, Entry> binding) {
        if (!Files.isDirectory(checkoutRoot)) {
            throw new IllegalArgumentException("checkout 根不存在: " + checkoutRoot);
        }
        this.checkoutRoot = checkoutRoot;
        this.binding = Map.copyOf(binding);
    }

    @Override
    public byte[] execute(ToolExecutor.ToolExecution execution) {
        ParsedRead read = parseArgs(execution.validatedArgs(), binding.keySet());
        Entry entry = binding.get(read.service());
        Path file = CodeSourceBinding.resolveFile(checkoutRoot, entry, read.path());
        if (file == null) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 文件在绑定源树内不存在或路径越界（沙箱拒绝如实呈现）");
        }
        ReadWindow window = readWindow(file, read);
        if (window.rows().isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 目标为二进制/空文件，无文本行可读（如实呈现）");
        }
        return render(entry, read, window, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 语义面（静态 = L0 可测）

    record ParsedRead(String service, String path, long lineStart, long lineEnd) {
    }

    /** 参数语义面：service 必在绑定集；行窗形状（1 起步、start ≤ end、窗幅 ≤ 上限） */
    static ParsedRead parseArgs(Map<String, Object> args, Set<String> allowedServices) {
        String service = text(args.get("service"));
        if (service == null || !allowedServices.contains(service)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 必在宿主绑定集内（未授权仓库即拒）");
        }
        String path = text(args.get("path"));
        if (path == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: path 必填（源树内相对路径）");
        }
        long lineStart = intArg(args.get("line_start"), 1);
        long lineEnd = intArg(args.get("line_end"), lineStart + MAX_LINES - 1);
        if (lineStart < 1 || lineEnd < lineStart || lineEnd - lineStart + 1 > MAX_LINES) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 行窗非法（1 ≤ line_start ≤ line_end，窗幅 ≤ "
                            + MAX_LINES + " 行）");
        }
        return new ParsedRead(service, path, lineStart, lineEnd);
    }

    private static long intArg(Object raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 行窗参数必为整数");
        }
    }

    record Row(long line, String text) {
    }

    record ReadWindow(List<Row> rows, long totalLines, boolean truncated) {
    }

    /**
     * 行窗读取：binary 拒（空窗 NO_DATA 语义）、字节上限内读取（超限截字节并如实
     * 标 truncated）、行号 1 起步。total_lines 为字节上限内可见行数（截字节时不谎称
     * 完整）；模型自己的行窗窄于文件不算截断。
     */
    static ReadWindow readWindow(Path file, ParsedRead read) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "SOURCE_UNAVAILABLE: 源文件读取不可用（数据面异常已脱敏）");
        }
        boolean byteClipped = bytes.length > MAX_READ_BYTES;
        if (byteClipped) {
            bytes = java.util.Arrays.copyOf(bytes, (int) MAX_READ_BYTES);
        }
        if (CodeSearchExecutor.isBinary(bytes)) {
            return new ReadWindow(List.of(), 0, false);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        // 尾随换行是行终止符而非新行（文本文件常规语义），去幻影末行保留段内空行
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        String[] lines = content.split("\n", -1);
        List<Row> rows = new ArrayList<>();
        long end = Math.min(read.lineEnd(), lines.length);
        for (long no = Math.max(read.lineStart(), 1); no <= end; no++) {
            rows.add(new Row(no, CodeSearchExecutor.redact(
                    CodeSearchExecutor.clip(lines[(int) (no - 1)]))));
        }
        return new ReadWindow(List.copyOf(rows), lines.length, byteClipped);
    }

    private byte[] render(Entry entry, ParsedRead read, ReadWindow window, long limitBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1_024);
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeStringField("path", read.path());
            gen.writeNumberField("total_lines", window.totalLines());
            gen.writeBooleanField("truncated", window.truncated());
            gen.writeFieldName("binding");
            CodeSearchExecutor.writeBinding(gen, entry);
            gen.writeFieldName("result");
            gen.writeStartArray();
            for (Row row : window.rows()) {
                gen.writeStartObject();
                gen.writeNumberField("line", row.line());
                gen.writeStringField("text", row.text());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeStringField("note", "部署版本代码取证（只读；宿主声明绑定，第一期无"
                    + "运行时镜像 digest 核验）。行内容已过秘密词形脱敏。");
            gen.writeEndObject();
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("code.read 结果序列化失败", e);
        }
        if (out.size() > Math.max(1, limitBytes)) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限");
        }
        return out.toByteArray();
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.strip();
    }
}
