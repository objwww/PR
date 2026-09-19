package com.objwww.pr.control.alert.application;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.objwww.pr.control.alert.domain.repository.RcaModelCallUsageReader;

/**
 * 模型调用失败码 → 中文直接原因/处理建议（码表词汇 = ModelRouter/
 * ProviderErrorClassifier 写面；随失败族扩，未知码如实透出不冒充）。
 *
 * <p>消费面：调查详情 failure 块与处置中心 situation 卡——回答"AI 为什么没取到
 * 有效证据"。账面 error_code 是唯一诚实来源（BA-168 纪律：不猜、不编）；
 * 零失败码 = 模型调用全部成功，此时"未取到足够证据"才是真实的证据问题，
 * 不在本词典管辖（维持双源佐证文案）。
 */
public final class ModelFailureGuide {

    private ModelFailureGuide() {
    }

    /** error_code → [直接原因, 处理建议] */
    private static final Map<String, String[]> GUIDE = Map.of(
            "BILLING_OR_ACTIVATION", new String[]{
                    "模型服务商账户欠费或能力未激活（HTTP 402），模型调用被拒绝",
                    "到服务商控制台充值/激活后重新调查即可，链路自动恢复、系统无需改动"},
            "AUTH_DENIED", new String[]{
                    "模型调用凭证无效或权限不足（鉴权拒绝）",
                    "检查模型 API Key 的有效期与权限配置，修复后重新调查"},
            "REQUEST_INVALID", new String[]{
                    "模型判定请求无效（参数/格式不被接受）",
                    "属系统侧缺陷，请联系开发排查模型请求构造；短期可人工研判告警"},
            "OUTPUT_BUDGET_EXHAUSTED", new String[]{
                    "模型单步输出预算耗尽（推理模型的思考链吃满输出额度）",
                    "调大单步输出预算（step-max-tokens）后重新调查"},
            "DEFERRED", new String[]{
                    "模型调用被延期（限流/拥塞，服务端要求稍后重试）",
                    "稍后重新调查；持续被限流请联系服务商提升额度"});

    /** 单码直接原因；未知码如实透出原值 */
    public static String reasonZh(String errorCode) {
        String[] g = GUIDE.get(errorCode);
        return g != null ? g[0] : "模型调用失败（错误码 " + errorCode + "）";
    }

    /** 单码处理建议；未知码给通用兜底 */
    public static String guidanceZh(String errorCode) {
        String[] g = GUIDE.get(errorCode);
        return g != null ? g[1] : "查看调用链错误详情后重新调查；持续失败请联系开发排查";
    }

    /** 失败分布 → 一句话直接原因（各码按次数列明；零失败 → null 调用方跳过） */
    public static String summaryReasonZh(List<RcaModelCallUsageReader.CallFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner("；");
        for (RcaModelCallUsageReader.CallFailure f : failures) {
            joiner.add(reasonZh(f.errorCode()) + "×" + f.count());
        }
        return joiner.toString();
    }
}
