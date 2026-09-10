package com.objwww.pr.control.infrastructure.tool;

import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Prometheus query_range 工具执行器（AM4 M4-27，首个真实工具）：
 * 纯远程调用面——GET {base}/api/v1/query_range?query&start&end&step，原样返回响应字节。
 * 策略/校验/账本全部归 Gateway 与 Agent（单一咽喉纪律）；HTTP 429 → RATE_LIMITED，
 * 其余非 200 / 网络异常 → REMOTE_UNAVAILABLE（脱敏固定文案，底层细节不透传）。
 *
 * <p>客户端请求超时 = deadline 剩余 + 缓冲（与 ToolGateway 硬 deadline 双定时器同窗会
 * 竞争，缓冲让 Gateway 确定性先到先取消——itW02 同纪律）。
 *
 * <p>EX-A4a（F17）有界读取：响应体以流式至多读 {@code resultLimitBytes+1} 字节，
 * 读到 limit+1 即断（关闭流不再消费连接）→ 控制面 RESULT_OVERSIZE——超大响应不再
 * 全量进内存。语义约束（executor 域内判）：start/end 必须整数 epoch 秒且窗幅
 * ≤ {@value #MAX_WINDOW_SECONDS}s；step ≤ {@value #MAX_STEP_SECONDS}s——schema 声明
 * 形状（pattern/maxLength），本类判语义，超限 INVALID_ARGS。
 */
public class PrometheusQueryExecutor implements ToolExecutor {

    private static final long CLIENT_TIMEOUT_BUFFER_MILLIS = 2_000;
    /** F17 语义窗幅上限（冻结窗 RANGE_WINDOW_SECS=600s 的宽裕上界） */
    static final long MAX_WINDOW_SECONDS = 3_600;
    /** F17 语义步长上限（InvestigationInputs.STEP=30s 的宽裕上界） */
    static final long MAX_STEP_SECONDS = 60;
    private static final int COPY_BUFFER_SIZE = 8 * 1_024;

    private final String baseUrl;
    private final HttpClient http;

    public PrometheusQueryExecutor(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    public PrometheusQueryExecutor(String baseUrl, HttpClient http) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
        if (baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("baseUrl 不得以 / 结尾");
        }
        this.http = Objects.requireNonNull(http);
    }

    @Override
    public byte[] execute(ToolExecution execution) throws Exception {
        Map<String, Object> args = execution.validatedArgs();
        validateSemantics(args);
        long remaining = Math.max(1,
                execution.deadlineEpochMillis() - System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/query_range?"
                + param("query", args.get("query")) + "&"
                + param("start", args.get("start")) + "&"
                + param("end", args.get("end")) + "&"
                + param("step", args.get("step"))))
                .timeout(Duration.ofMillis(remaining + CLIENT_TIMEOUT_BUFFER_MILLIS))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用被中断（临时远端故障）");
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具远端暂不可用（临时故障，可重试）");
        }
        if (response.statusCode() == 429) {
            response.body().close();
            throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                    "指标源限流（可退避重试）");
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具远端暂不可用（临时故障，可重试）");
        }
        // F17 有界流读：limit+1 探针——读到即断，超大响应不进内存
        return readBounded(response.body(), execution.resultLimitBytes());
    }

    /** F17 语义校验：epoch 秒窗幅 ≤3600s、step ≤60s（schema pattern 管形状，此处管语义） */
    private static void validateSemantics(Map<String, Object> args) {
        long start = parseEpochSeconds(args.get("start"), "start");
        long end = parseEpochSeconds(args.get("end"), "end");
        if (end - start > MAX_WINDOW_SECONDS || end - start < 0) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（end-start ≤ " + MAX_WINDOW_SECONDS + "s）");
        }
        long step = parseStepSeconds(String.valueOf(args.get("step")));
        if (step > MAX_STEP_SECONDS) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: step 超限（≤ " + MAX_STEP_SECONDS + "s）");
        }
    }

    private static long parseEpochSeconds(Object value, String field) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必须为整数 epoch 秒");
        }
    }

    /** step 形如 "30s"/"1m"（schema pattern 已管形状；此处换算秒判语义上限） */
    private static long parseStepSeconds(String step) {
        if (step == null || step.length() < 2) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: step 形如 30s/1m");
        }
        String unit = step.substring(step.length() - 1);
        long magnitude;
        try {
            magnitude = Long.parseLong(step.substring(0, step.length() - 1));
        } catch (NumberFormatException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: step 形如 30s/1m");
        }
        return switch (unit) {
            case "s" -> magnitude;
            case "m" -> magnitude * 60;
            case "h" -> magnitude * 3_600;
            default -> throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: step 单位仅 s/m/h");
        };
    }

    /** 至多读 limit+1 字节；读到 limit+1 = 超限即断（控制面终止族，同 Gateway 裁断语义） */
    private static byte[] readBounded(InputStream body, long limitBytes)
            throws IOException {
        try (body) {
            long cap = Math.max(1, limitBytes) + 1;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.min(cap, 1 << 20));
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                long allowed = cap - total;
                if (read > allowed) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（有界流读即断）");
                }
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        }
    }

    private static String param(String name, Object value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }
}
