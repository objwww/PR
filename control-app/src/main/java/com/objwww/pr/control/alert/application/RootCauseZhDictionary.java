package com.objwww.pr.control.alert.application;

import java.util.Map;

/**
 * 根因报告中文字典（人读面）：fault_type / 症状码（告警名）/ 组件 / 证据来源
 * 四类机器码 → 中文自然表达。数据与权威来源逐条对齐：
 * <ul>
 *   <li>fault_type 15 值 = deploy/alert/eval/synonym-lexicon-v1.yml（lexicon_version 2，
 *       UPPER_SNAKE 5 值 + business.* 10 值）——name/explanation 取自 description，
 *       remediation = 按故障类型给出的<b>通用处置方向</b>（非本系统执行动作，
 *       不编造具体参数）；</li>
 *   <li>症状码（告警名）= alert-web/src/dict/scenarioZh.js ALERTNAME_ZH 抄义；</li>
 *   <li>组件 = synonym-lexicon-v1.yml components 节（payment/order-arena）+
 *       告警面服务 checkout；</li>
 *   <li>证据来源 = 调查链实际 source 标签（prometheus/logs/change 等）。</li>
 * </ul>
 * 纪律：未命中一律回退原文（绝不猜测翻译，同前端 displayNameZh/scenarioZh 纪律）；
 * 词典只增不改，扩容随 synonym-lexicon 版本演进。
 */
public final class RootCauseZhDictionary {

    /** fault_type 中文面：中文名 + 一句话解释 + 通用处置方向 */
    public record FaultTypeZh(String name, String explanation, String remediation) {
    }

    private static final Map<String, FaultTypeZh> FAULT_TYPES = Map.ofEntries(
            Map.entry("BUSINESS_ERROR_RATE", new FaultTypeZh("业务错误率升高",
                    "依赖自身可达、无基础设施故障，业务调用按比例失败",
                    "核对近期发布与特性开关变更，回滚或下调失败比例，并补充失败率告警")),
            Map.entry("DEPENDENCY_UNREACHABLE", new FaultTypeZh("依赖服务不可达",
                    "下游依赖连接失败/服务不可用，上游调用成批报错",
                    "检查下游服务健康与网络连通，必要时对该依赖降级或走重启审批")),
            Map.entry("IDEMPOTENCY_BYPASS", new FaultTypeZh("幂等失效",
                    "幂等机制被绕过，同一请求被重复执行，产生重复记录",
                    "拦截重复流量，修复幂等键校验逻辑，对重复数据做对账清理")),
            Map.entry("ILLEGAL_STATE_TRANSITION", new FaultTypeZh("非法状态迁移",
                    "绕过状态机直接改写状态，或状态与台账事实矛盾",
                    "冻结直接写库通道，修复状态机校验，按台账事实修复回跳数据")),
            Map.entry("UNCERTAIN_TIMEOUT", new FaultTypeZh("超时结果未知",
                    "调用超时且结果未知，事务悬挂在中间态",
                    "触发对账作业收敛悬挂单，核查超时配置与下游响应")),
            Map.entry("business.order_lost", new FaultTypeZh("掉单（支付成功订单未推进）",
                    "支付侧成功但订单侧未推进，技术指标全绿只有业务对账暴露",
                    "核对支付回调收口链路，补偿推进掉单订单")),
            Map.entry("business.payment_pending", new FaultTypeZh("支付悬挂",
                    "支付发起沉默——不发起网关请求也不报错，待支付订单积压",
                    "检查支付发起链路故障点，恢复后清理待支付积压")),
            Map.entry("business.duplicate_charge", new FaultTypeZh("重复扣款",
                    "同一订单多笔支付成功（支付层幂等失效）",
                    "拦截重复重试，对已多扣款项发起退款并对账")),
            Map.entry("business.reconciliation_mismatch", new FaultTypeZh("三方对账不平",
                    "订单-支付-库存三方记账数量不一致",
                    "运行对账作业定位差异行，补齐缺失记账")),
            Map.entry("business.inventory_oversell", new FaultTypeZh("库存超卖",
                    "库存预占竞态致同订单多笔扣减/负库存",
                    "冻结超卖库存的后续履约，修复原子扣减（check-then-act）")),
            Map.entry("business.event_lost", new FaultTypeZh("下单事件丢失",
                    "订单已创建但履约下游零感知，无错误日志",
                    "补投丢失事件或人工触发履约，核查消息发送链路")),
            Map.entry("business.duplicate_fulfillment", new FaultTypeZh("重复履约",
                    "消费幂等缺失，同一履约单被重复执行",
                    "为消费端补 ack 幂等闸，回收重复履约产物")),
            Map.entry("business.kpi_degradation", new FaultTypeZh("下单成功率下跌",
                    "多因素低剂量叠加，单条技术指标均不越阈",
                    "拆分各因素贡献逐一治理，优先回滚最近变更")),
            Map.entry("business.zero_flow", new FaultTypeZh("断流（零订单）",
                    "创单入口静默致零订单，须先与指标采集断流鉴别",
                    "先排除指标采集中断（探针自证），再恢复创单入口")),
            Map.entry("business.fulfillment_timeout", new FaultTypeZh("履约超时积压",
                    "履约收口变慢致 SLA 超时积压，下单链路正常",
                    "扩容履约收口作业或人工催办积压单")));

    /** 症状码（告警名）→ 中文名（抄义 alert-web/src/dict/scenarioZh.js ALERTNAME_ZH） */
    private static final Map<String, String> SYMPTOMS = Map.ofEntries(
            Map.entry("checkout", "结算可用性烧损"),
            Map.entry("ArenaDuplicateOrders", "订单重复创建"),
            Map.entry("ArenaIllegalTransitions", "订单状态非法迁移"),
            Map.entry("ArenaOrderStuck", "订单卡顿悬挂"),
            Map.entry("ArenaPaymentOrderMismatch", "支付成功但订单未推进"),
            Map.entry("ArenaPendingPaymentBacklog", "待支付订单积压"),
            Map.entry("ArenaDuplicatePayments", "重复扣款"),
            Map.entry("ArenaReconciliationDiff", "三方对账不平"),
            Map.entry("ArenaOversell", "库存超卖"),
            Map.entry("ArenaFulfillmentGap", "履约缺口（下单未触发履约）"),
            Map.entry("ArenaDuplicateFulfillment", "重复履约"),
            Map.entry("ArenaOrderSuccessRateLow", "下单成功率下跌"),
            Map.entry("ArenaOrderZeroFlow", "订单断流（零订单）"),
            Map.entry("ArenaFulfillmentSlaBreach", "履约超时积压"),
            Map.entry("ArenaDomainProbeDown", "业务探针失联"),
            Map.entry("CheckoutRpcClientErrorRateHigh", "结算下游调用错误率高"),
            Map.entry("FrontendEventLoopP99High", "前端事件循环 P99 延迟高"),
            Map.entry("FrontendHttp5xxRateElevated", "前端 5xx 错误率升高"),
            Map.entry("FrontendHttp5xxRateHigh", "前端 5xx 错误率高"),
            Map.entry("MemoryGateTier1", "内存闸门一级"),
            Map.entry("MemoryGateTier2", "内存闸门二级"),
            Map.entry("MemoryGateTier3", "内存闸门三级"));

    /** 组件 → 中文服务名（synonym-lexicon-v1.yml components 节 + 告警面 checkout） */
    private static final Map<String, String> COMPONENTS = Map.of(
            "payment", "支付服务",
            "order-arena", "订单服务",
            "checkout", "结算服务");

    /** 证据来源标签 → 中文名（调查链实际 source 面） */
    private static final Map<String, String> SOURCES = Map.of(
            "prometheus", "指标",
            "logs", "日志",
            "loki", "日志",
            "change", "变更记录",
            "mutation", "审批处置");

    private RootCauseZhDictionary() {
    }

    /** fault_type 中文面；未命中 → null（调用面回退原文，绝不猜测翻译） */
    public static FaultTypeZh faultType(String code) {
        return code == null ? null : FAULT_TYPES.get(code);
    }

    /** fault_type 中文名；未命中回退原文码 */
    public static String faultTypeName(String code) {
        FaultTypeZh zh = faultType(code);
        return zh == null ? code : zh.name();
    }

    /** 症状码（告警名）中文名；未命中回退原文 */
    public static String symptomName(String code) {
        return SYMPTOMS.getOrDefault(code, code);
    }

    /** 组件中文服务名；未命中回退原文 */
    public static String componentName(String code) {
        return code == null ? "unknown" : COMPONENTS.getOrDefault(code, code);
    }

    /** 证据来源中文名；未命中回退原文 */
    public static String sourceName(String code) {
        return SOURCES.getOrDefault(code, code);
    }
}
