package com.objwww.pr.control.alert.domain.agent;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * R2 输入捕获端口（V90 rca_model_input，append-only）：RCA 模型调用的 prompt
 * 落档三档（对齐 OTel GenAI capture message content opt-in 惯例）——
 * <ul>
 *   <li>FULL 原文落库（评测/排障环境覆写）；</li>
 *   <li>REDACTED 脱敏文落库（发送前 mask 密钥值——LangSmith mask 惯例，方案词
 *       形保留，不事后删）；</li>
 *   <li>DIGEST_ONLY 零原文（生产默认；只有 digest 与字节量，账行 prompt_digest
 *       可对账）。</li>
 * </ul>
 * 红线：prompt_digest 恒为对<b>原始 prompt</b> 算的 sha256——REDACTED 档存储文本
 * 与 digest 天然不等 = 回放器如实标"不完整"，不伪造一致（脱敏不可反推原文）。
 */
public interface RcaModelInputCapture {

    /** append-only 落档（写失败由调用方按账本同律零触网） */
    void capture(CaptureRow row);

    /** 本端口实例的捕获档位（装配期钉定） */
    Level level();

    enum Level {FULL, REDACTED, DIGEST_ONLY}

    /**
     * 捕获行（FULL ⇒ 原文必在；DIGEST_ONLY ⇒ 原文必空——CHECK 两向钉的领域侧同构）。
     * promptDigest 恒为原始 prompt 摘要（调用方网关算好传入，本类不改写）。
     */
    record CaptureRow(UUID modelCallId, Level level, String promptText,
                      String promptDigest, Integer messageBytes, Integer approxTokens,
                      String redactionNote) {

        public CaptureRow {
            Objects.requireNonNull(modelCallId, "modelCallId");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(promptDigest, "promptDigest");
            if (level == Level.FULL && promptText == null) {
                throw new IllegalArgumentException("FULL 档必须落原文");
            }
            if (level == Level.DIGEST_ONLY && promptText != null) {
                throw new IllegalArgumentException("DIGEST_ONLY 档不得落原文");
            }
        }
    }

    /**
     * 构造捕获行（发送前调用）：FULL 落原文；REDACTED 落脱敏文（密钥<b>值</b>零出现，
     * "Bearer ***" 方案词形保留）；DIGEST_ONLY 零原文。messageBytes/approxTokens
     * 恒按原始 prompt 计（与存储档无关，供容量画像）。
     */
    static CaptureRow buildRow(UUID modelCallId, Level level, String prompt,
            String promptDigest) {
        int messageBytes = prompt.getBytes(StandardCharsets.UTF_8).length;
        int approxTokens = prompt.length() / 2 + 1;
        return switch (level) {
            case FULL -> new CaptureRow(modelCallId, level, prompt, promptDigest,
                    messageBytes, approxTokens, null);
            case REDACTED -> {
                String masked = maskSecrets(prompt);
                yield new CaptureRow(modelCallId, level, masked, promptDigest,
                        messageBytes, approxTokens,
                        masked.equals(prompt) ? null
                                : "masked（密钥值替换 ***；方案词形保留，LangSmith 惯例）");
            }
            case DIGEST_ONLY -> new CaptureRow(modelCallId, level, null, promptDigest,
                    messageBytes, approxTokens, null);
        };
    }

    /** Bearer 凭据：方案词保留，凭据值整体替换（sk- 前缀密钥同被覆盖） */
    Pattern BEARER_VALUE = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    /** 独立 sk- 形态密钥（无 Bearer 前缀的裸值） */
    Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9]{8,}");
    /** 密钥字段 JSON 形态：键名保留，字符串值替换 */
    Pattern SECRET_FIELD_JSON = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|apikey|secret|password|private[_-]?key|token)\""
                    + "\\s*:\\s*)\"[^\"]*\"");
    /** 密钥字段键值形态：键名保留，取值替换 */
    Pattern SECRET_FIELD_KV = Pattern.compile(
            "(?i)\\b((?:api[_-]?key|apikey|secret|password|private[_-]?key|token)"
                    + "\\s*=\\s*)\"?[A-Za-z0-9._~+/=-]+\"?");

    /** 脱敏（REDACTED 档）：只动值不动键——结构可读、密钥零出现 */
    static String maskSecrets(String prompt) {
        String masked = BEARER_VALUE.matcher(prompt).replaceFirst("$1***");
        masked = SK_KEY.matcher(masked).replaceAll("sk-***");
        masked = SECRET_FIELD_JSON.matcher(masked).replaceFirst("$1\"***\"");
        masked = SECRET_FIELD_KV.matcher(masked).replaceFirst("$1***");
        return masked;
    }
}
