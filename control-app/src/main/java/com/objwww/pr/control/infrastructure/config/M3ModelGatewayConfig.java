package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.application.ModelCallLedgerRecovery;
import com.objwww.pr.control.application.ModelGateway;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.ModelGatewayParams;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.RouteClientPort;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.infrastructure.model.RawHttpErrorCapture;
import com.objwww.pr.control.infrastructure.model.SpringAiRouteClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * M3 模型治理装配（§4.8/§4.9，仅 docker profile——与 PersistenceConfig 同理：
 * 无 DataSource 的默认 profile 下账本 repository 无从装配，Gateway 随之不暴露）。
 *
 * <p>手工装配（T00/F-10：1.0.0 自动配置只产一个 OpenAiChatModel，双路由必须手工）：
 * 每路由独立 OpenAiApi/ChatModel/RouteClient，显式 RetryTemplate maxAttempts(1) +
 * RawHttpErrorCapture（分类只认原始 HTTP 事实）+ JdkClientHttpRequestFactory 两层超时
 * （connect 10s 兜底 + read = per-call-timeout，§4.10：socket 兜底不得先于领域超时）。
 *
 * <p>半配置规则（裁定 C-2）：AGENT_MODEL_FALLBACK 空 = 单路由合法；非空而端点/key
 * 未显式配置 = 继承主路由合法；key 空白/占位符拒绝启动且日志不回显（EX-45）。
 */
@Configuration
@Profile("docker")
public class M3ModelGatewayConfig {

    /** 占位符字样（与 application.yml 默认值一致；命中即拒绝启动，不回显 key 本体） */
    private static final String PLACEHOLDER = "placeholder-not-configured";
    /** 连接超时兜底（§4.10：默认 10s） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** F-22 租约不等式的心跳余量：max-lease 必须大于 Gateway 总 deadline + 该余量 */
    private static final long LEASE_MARGIN_MS = 10_000;

    @Bean
    @ConfigurationProperties(prefix = "app.model")
    public ModelGatewayProperties modelGatewayProperties() {
        return new ModelGatewayProperties();
    }

    @Bean
    public PricingService pricingService(ModelGatewayProperties props) {
        Map<String, PricingService.PriceEntry> prices = new HashMap<>();
        props.getPrice().forEach((model, cfg) -> {
            if (cfg.getInputMicrosPer1k() <= 0 && cfg.getOutputMicrosPer1k() <= 0) {
                return; // 单价未配置 = 不估算（§4.9 默认 0）
            }
            prices.put(model, new PricingService.PriceEntry(
                    cfg.getPricingVersion(), cfg.getCurrency(),
                    cfg.getInputMicrosPer1k(), cfg.getOutputMicrosPer1k()));
        });
        return new PricingService(prices);
    }

    @Bean
    public ModelGateway modelGateway(
            ModelGatewayProperties props,
            ModelCallLedgerRepository ledgerRepository,
            ExecutionLedger executionLedger,
            PricingService pricingService,
            ObjectMapper objectMapper,
            Environment env,
            @Value("${AGENT_MODEL:qwen-plus}") String primaryModel,
            @Value("${AGENT_MODEL_FALLBACK:}") String fallbackModel,
            @Value("${OPENAI_COMPAT_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}")
            String primaryBaseUrl,
            @Value("${OPENAI_COMPAT_BASE_URL_FALLBACK:}") String fallbackBaseUrl,
            @Value("${AGENT_MODEL_API_KEY:placeholder-not-configured}") String primaryApiKey,
            @Value("${AGENT_MODEL_API_KEY_FALLBACK:}") String fallbackApiKey,
            // V4 兼容（v1.4 留痕）：契约身份默认值必须与 M2 接线一致——
            // provider=app.review.model-provider(openai-compatible)、
            // contract-version=app.review.model-version(configured)，主路由不变时旧 checkpoint 可复用
            @Value("${app.review.model-provider:openai-compatible}") String provider,
            @Value("${app.review.model-version:configured}") String contractVersion,
            @Value("${app.worker.max-lease-seconds:600}") int maxLeaseSeconds) {

        // ---- 启动校验（§4.9 清单；全部不回显密钥本体，EX-45） ----
        assertHiddenRetryDisabled(env);
        String primaryKey = requireRealKey(primaryApiKey, "AGENT_MODEL_API_KEY");

        boolean singleRoute = fallbackModel == null || fallbackModel.isBlank();
        // C-2 继承：备模型非空而端点/key 未显式配置 → 继承主路由
        String fallbackUrl = singleRoute || fallbackBaseUrl == null || fallbackBaseUrl.isBlank()
                ? primaryBaseUrl : fallbackBaseUrl;
        String fallbackKey = singleRoute || fallbackApiKey == null || fallbackApiKey.isBlank()
                ? primaryKey : requireRealKey(fallbackApiKey, "AGENT_MODEL_API_KEY_FALLBACK");

        ModelGatewayParams params = new ModelGatewayParams(
                props.getMaxCallRetries(),
                props.getBudget().getMaxPhysicalCallsPerStep(),
                props.getBudget().getMaxPromptTokensPerCall(),
                props.getBudget().getMaxCompletionTokensPerCall(),
                props.getBudget().getMaxTotalTokensPerStep(),
                Duration.ofMillis(props.getGateway().getTotalDeadlineMs()),
                Duration.ofMillis(props.getGateway().getInlineRetryMaxDelayMs()),
                Duration.ofMillis(props.getPerCallTimeoutMs()),
                props.getCircuit().getFailureThreshold(),
                Duration.ofSeconds(props.getCircuit().getCoolDownSeconds()),
                Duration.ofMillis(props.getRetry().getBackoffBaseMs()),
                Duration.ofMillis(props.getRetry().getBackoffMaxMs()),
                provider, contractVersion);

        // recovery-after ≥ 2×per-call-timeout（防把在途调用误标 UNKNOWN）
        if (props.getLedger().getRecoveryAfterSeconds() < 2 * params.perCallTimeout().getSeconds()) {
            throw new IllegalStateException(
                    "app.model.ledger.recovery-after-seconds 必须 >= 2 × per-call-timeout");
        }
        // F-22：租约必须先于 Gateway 总时限存活（含心跳余量），否则在途调用被接管者重复发起
        if (maxLeaseSeconds * 1000L <= params.gatewayTotalDeadline().toMillis() + LEASE_MARGIN_MS) {
            throw new IllegalStateException(
                    "app.worker.max-lease-seconds 必须大于 app.model.gateway.total-deadline-ms + 心跳余量");
        }

        // ---- 路由身份铸造（域从配置派生：endpoint 规范化；quota/credential 域 = key 哈希截断） ----
        ModelRoute primaryRoute = new ModelRoute(
                props.getRoute().getPrimaryId(), primaryModel,
                normalizeEndpoint(primaryBaseUrl), deriveScope("quota", primaryKey),
                deriveScope("cred", primaryKey), pricingVersionOf(props, primaryModel));
        ModelRoute fallbackRoute = null;
        if (!singleRoute) {
            if (props.getRoute().getPrimaryId().equals(props.getRoute().getFallbackId())) {
                throw new IllegalStateException("主备 route_id 相同，防伪 fallback 拒绝启动（EX-42）");
            }
            if (primaryModel.equals(fallbackModel)
                    && normalizeEndpoint(primaryBaseUrl).equals(normalizeEndpoint(fallbackUrl))
                    && primaryKey.equals(fallbackKey)) {
                throw new IllegalStateException("主备五元组完全相同，fallback 无意义，拒绝启动（EX-42）");
            }
            fallbackRoute = new ModelRoute(
                    props.getRoute().getFallbackId(), fallbackModel,
                    normalizeEndpoint(fallbackUrl), deriveScope("quota", fallbackKey),
                    deriveScope("cred", fallbackKey), pricingVersionOf(props, fallbackModel));
        }

        // ---- 每路由独立 RouteClient（T00：互不串、各自模型/超时独立生效） ----
        RouteClientPort primaryClient = createRouteClient(primaryBaseUrl, primaryKey, primaryModel,
                primaryRoute.routeId(), params.perCallTimeout(), objectMapper);
        RouteClientPort fallbackClient = fallbackRoute == null ? null
                : createRouteClient(fallbackUrl, fallbackKey, fallbackModel,
                        fallbackRoute.routeId(), params.perCallTimeout(), objectMapper);

        return new ModelGateway(primaryRoute, fallbackRoute, primaryClient, fallbackClient,
                params, ledgerRepository, pricingService, executionLedger);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public ModelCallLedgerRecovery modelCallLedgerRecovery(ModelCallLedgerRepository ledgerRepository,
                                                           ModelGatewayProperties props) {
        return new ModelCallLedgerRecovery(ledgerRepository,
                Duration.ofSeconds(props.getLedger().getRecoveryAfterSeconds()),
                props.getLedger().getRecoveryScanIntervalMs());
    }

    // ------------------------------------------------------------------ 内部

    /** I34/INC-42：Spring AI 隐藏重试必须关闭（官方默认 10 次），断言唯一开关 = 1。 */
    private static void assertHiddenRetryDisabled(Environment env) {
        String value = env.getProperty("spring.ai.retry.max-attempts", "1");
        if (!"1".equals(value.trim())) {
            throw new IllegalStateException(
                    "spring.ai.retry.max-attempts 必须为 1（关闭 Spring AI 隐藏重试），当前: " + value);
        }
    }

    /** key 空白/占位符/未解析占位符（含 "${"）→ 拒绝启动；错误信息不回显 key 本体（EX-45）。 */
    private static String requireRealKey(String key, String envName) {
        if (key == null || key.isBlank() || PLACEHOLDER.equals(key.trim())
                || "placeholder".equals(key.trim()) || key.contains("${")) {
            throw new IllegalStateException(envName + " 缺失或为占位符，拒绝启动（不回显密钥）");
        }
        return key.trim();
    }

    private RouteClientPort createRouteClient(String baseUrl, String apiKey, String model,
                                              String routeId, Duration perCallTimeout,
                                              ObjectMapper objectMapper) {
        // §4.10 两层超时：connect 10s（JDK HttpClient 级）+ read = per-call-timeout
        // （socket 兜底不得先于领域超时触发；Future.get 领域超时在 SpringAiRouteClient）
        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(perCallTimeout);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .responseErrorHandler(new RawHttpErrorCapture()) // F-17：原始 HTTP 事实进异常
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()) // I34 双保险
                .observationRegistry(ObservationRegistry.NOOP)
                .toolCallingManager(DefaultToolCallingManager.builder().build())
                .build();
        return new SpringAiRouteClient(chatModel, routeId, model, objectMapper);
    }

    private static String pricingVersionOf(ModelGatewayProperties props, String model) {
        PriceEntryConfig cfg = props.getPrice().get(model);
        return cfg == null ? null : cfg.getPricingVersion();
    }

    private static String normalizeEndpoint(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String normalized = baseUrl.trim().toLowerCase();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** quota/credential 域 = api-key SHA-256 截断派生值（永不落明文 key，§4.3/AFT-28）。 */
    private static String deriveScope(String prefix, String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(md.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
            return prefix + "_" + hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ------------------------------------------------------------------ 属性（§4.9 旋钮）

    public static class ModelGatewayProperties {
        private Route route = new Route();
        private int maxCallRetries = 2;
        private Budget budget = new Budget();
        private Gateway gateway = new Gateway();
        private long perCallTimeoutMs = 120_000;
        private Circuit circuit = new Circuit();
        private Ledger ledger = new Ledger();
        private Retry retry = new Retry();
        private Map<String, PriceEntryConfig> price = new LinkedHashMap<>();

        public Route getRoute() { return route; }
        public void setRoute(Route route) { this.route = route; }
        public int getMaxCallRetries() { return maxCallRetries; }
        public void setMaxCallRetries(int maxCallRetries) { this.maxCallRetries = maxCallRetries; }
        public Budget getBudget() { return budget; }
        public void setBudget(Budget budget) { this.budget = budget; }
        public Gateway getGateway() { return gateway; }
        public void setGateway(Gateway gateway) { this.gateway = gateway; }
        public long getPerCallTimeoutMs() { return perCallTimeoutMs; }
        public void setPerCallTimeoutMs(long perCallTimeoutMs) { this.perCallTimeoutMs = perCallTimeoutMs; }
        public Circuit getCircuit() { return circuit; }
        public void setCircuit(Circuit circuit) { this.circuit = circuit; }
        public Ledger getLedger() { return ledger; }
        public void setLedger(Ledger ledger) { this.ledger = ledger; }
        public Retry getRetry() { return retry; }
        public void setRetry(Retry retry) { this.retry = retry; }
        public Map<String, PriceEntryConfig> getPrice() { return price; }
        public void setPrice(Map<String, PriceEntryConfig> price) { this.price = price; }
    }

    public static class Route {
        private String primaryId = "primary";
        private String fallbackId = "fallback";

        public String getPrimaryId() { return primaryId; }
        public void setPrimaryId(String primaryId) { this.primaryId = primaryId; }
        public String getFallbackId() { return fallbackId; }
        public void setFallbackId(String fallbackId) { this.fallbackId = fallbackId; }
    }

    public static class Budget {
        private int maxPhysicalCallsPerStep = 6;
        private int maxPromptTokensPerCall = 100_000;
        private int maxCompletionTokensPerCall = 16_000;
        private int maxTotalTokensPerStep = 120_000;

        public int getMaxPhysicalCallsPerStep() { return maxPhysicalCallsPerStep; }
        public void setMaxPhysicalCallsPerStep(int v) { this.maxPhysicalCallsPerStep = v; }
        public int getMaxPromptTokensPerCall() { return maxPromptTokensPerCall; }
        public void setMaxPromptTokensPerCall(int v) { this.maxPromptTokensPerCall = v; }
        public int getMaxCompletionTokensPerCall() { return maxCompletionTokensPerCall; }
        public void setMaxCompletionTokensPerCall(int v) { this.maxCompletionTokensPerCall = v; }
        public int getMaxTotalTokensPerStep() { return maxTotalTokensPerStep; }
        public void setMaxTotalTokensPerStep(int v) { this.maxTotalTokensPerStep = v; }
    }

    public static class Gateway {
        private long totalDeadlineMs = 300_000;
        private long inlineRetryMaxDelayMs = 15_000;

        public long getTotalDeadlineMs() { return totalDeadlineMs; }
        public void setTotalDeadlineMs(long v) { this.totalDeadlineMs = v; }
        public long getInlineRetryMaxDelayMs() { return inlineRetryMaxDelayMs; }
        public void setInlineRetryMaxDelayMs(long v) { this.inlineRetryMaxDelayMs = v; }
    }

    public static class Circuit {
        private int failureThreshold = 3;
        private long coolDownSeconds = 60;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int v) { this.failureThreshold = v; }
        public long getCoolDownSeconds() { return coolDownSeconds; }
        public void setCoolDownSeconds(long v) { this.coolDownSeconds = v; }
    }

    public static class Ledger {
        private long recoveryAfterSeconds = 240;
        private long recoveryScanIntervalMs = 60_000;

        public long getRecoveryAfterSeconds() { return recoveryAfterSeconds; }
        public void setRecoveryAfterSeconds(long v) { this.recoveryAfterSeconds = v; }
        public long getRecoveryScanIntervalMs() { return recoveryScanIntervalMs; }
        public void setRecoveryScanIntervalMs(long v) { this.recoveryScanIntervalMs = v; }
    }

    public static class Retry {
        private long backoffBaseMs = 1_000;
        private long backoffMaxMs = 60_000;

        public long getBackoffBaseMs() { return backoffBaseMs; }
        public void setBackoffBaseMs(long v) { this.backoffBaseMs = v; }
        public long getBackoffMaxMs() { return backoffMaxMs; }
        public void setBackoffMaxMs(long v) { this.backoffMaxMs = v; }
    }

    /** 单价表条目（app.model.price.<model>.*；双单价均为 0 = 不估算） */
    public static class PriceEntryConfig {
        private String pricingVersion = "unpriced";
        private String currency = "USD";
        private long inputMicrosPer1k = 0;
        private long outputMicrosPer1k = 0;

        public String getPricingVersion() { return pricingVersion; }
        public void setPricingVersion(String pricingVersion) { this.pricingVersion = pricingVersion; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public long getInputMicrosPer1k() { return inputMicrosPer1k; }
        public void setInputMicrosPer1k(long v) { this.inputMicrosPer1k = v; }
        public long getOutputMicrosPer1k() { return outputMicrosPer1k; }
        public void setOutputMicrosPer1k(long v) { this.outputMicrosPer1k = v; }
    }
}
