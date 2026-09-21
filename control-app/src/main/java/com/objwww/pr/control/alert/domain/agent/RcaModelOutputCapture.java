package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.shared.Digest;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * 输出捕获端口（V167 rca_model_output，append-only）：R2 输入捕获
 * （{@link RcaModelInputCapture}，V90）的对称面——A/B 对照实验要求每步模型
 * 推理的回答可展示，此前输出不落库。档位与输入同族外加 OFF（配置键
 * {@code app.alert.r7.output-capture: off|full|redacted|digest-only}，
 * 默认 off）：
 * <ul>
 *   <li>OFF 零行档（默认）：一行不落（连 digest 行也不落）——输出捕获是
 *       展示增益不是账本依赖，默认面零写入零失败面；</li>
 *   <li>FULL 原文落库（评测/对照实验环境覆写）；</li>
 *   <li>REDACTED 脱敏文落库（复用输入捕获同款掩敏器
 *       {@link RcaModelInputCapture#maskSecrets}，密钥值零出现）；</li>
 *   <li>DIGEST_ONLY 零原文（只有 digest 与字节量）。</li>
 * </ul>
 * 红线（与输入捕获同律）：output_digest 恒为对<b>原始响应文本</b>算的
 * sha256——REDACTED 档存储文本与 digest 天然不等 = 读面如实标"已掩敏"，
 * 不伪造一致（脱敏不可反推原文）。
 *
 * <p>失败路径语义（javadoc 钉定）：模型调用 FAILED/TIMEOUT 无输出产出 →
 * <b>不落行</b>（诚实"无输出"，不伪造错误文本充数）；故本表只对 SUCCESS
 * 调用有行。与输入捕获"发送前必落"的不对称是诚实的：输入恒存在，输出仅
 * 成功时存在。
 */
public interface RcaModelOutputCapture {

    /** append-only 落档（写失败由调用方按输入捕获同律处理：账记因+成功结果不放行） */
    void capture(CaptureRow row);

    /** 本端口实例的捕获档位（装配期钉定） */
    Level level();

    /** OFF 为零行档不落库，故 CHECK 值域只钉三档可存储级别（V167） */
    enum Level {OFF, FULL, REDACTED, DIGEST_ONLY}

    /** OFF 档单例：零行零写面（capture 被调用属装配纰漏，静默丢弃——网关按 level 守卫在先） */
    RcaModelOutputCapture OFF = new RcaModelOutputCapture() {
        @Override
        public void capture(CaptureRow row) {
            // 零行档：不落行
        }

        @Override
        public Level level() {
            return Level.OFF;
        }
    };

    /**
     * 捕获行（与 rca_model_input 同构两向钉：FULL ⇒ 原文必在；DIGEST_ONLY ⇒
     * 原文必空；OFF 禁落行）。outputDigest 恒为原始响应摘要（本类算，调用方不传）。
     */
    record CaptureRow(UUID modelCallId, Level level, String outputText,
                      String outputDigest, Integer messageBytes, Integer approxTokens,
                      String redactionNote) {

        public CaptureRow {
            Objects.requireNonNull(modelCallId, "modelCallId");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(outputDigest, "outputDigest");
            if (level == Level.OFF) {
                throw new IllegalArgumentException("OFF 档不落行（零行档无捕获行）");
            }
            if (level == Level.FULL && outputText == null) {
                throw new IllegalArgumentException("FULL 档必须落原文");
            }
            if (level == Level.DIGEST_ONLY && outputText != null) {
                throw new IllegalArgumentException("DIGEST_ONLY 档不得落原文");
            }
        }
    }

    /**
     * 构造捕获行（响应到手后调用）：FULL 落原文；REDACTED 落脱敏文（复用输入
     * 捕获掩敏器，密钥值零出现）；DIGEST_ONLY 零原文；OFF 直接拒绝（调用方
     * 应先按 level 守卫）。messageBytes/approxTokens 恒按原始响应计（与存储档
     * 无关，供容量画像）；outputDigest 恒为原始响应摘要。
     */
    static CaptureRow buildRow(UUID modelCallId, Level level, String output) {
        Objects.requireNonNull(output, "output");
        String outputDigest = Digest.sha256Of(output).value();
        int messageBytes = output.getBytes(StandardCharsets.UTF_8).length;
        int approxTokens = output.length() / 2 + 1;
        return switch (level) {
            case FULL -> new CaptureRow(modelCallId, level, output, outputDigest,
                    messageBytes, approxTokens, null);
            case REDACTED -> {
                String masked = RcaModelInputCapture.maskSecrets(output);
                yield new CaptureRow(modelCallId, level, masked, outputDigest,
                        messageBytes, approxTokens,
                        masked.equals(output) ? null
                                : "masked（密钥值替换 ***；方案词形保留，LangSmith 惯例）");
            }
            case DIGEST_ONLY -> new CaptureRow(modelCallId, level, null, outputDigest,
                    messageBytes, approxTokens, null);
            case OFF -> throw new IllegalArgumentException("OFF 档不落行（零行档无捕获行）");
        };
    }
}
