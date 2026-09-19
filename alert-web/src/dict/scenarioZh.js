// 评测场景 / 告警名中文词典（前端产品化：故障名/场景名一律中文自然描述上屏，
// 不裸显 scenarioId/alertname；机器码以副行或 tooltip 保留对账能力）。
// 权威来源：deploy/alert/eval/eval-scenarios.yml（场景 name/scenario_type/chaos_family/injection）
// 与 deploy/alert/prometheus/rules/*.yml（alertname 落码规则）。
// 未命中一律回退原文/原 id，不猜测翻译（同 displayNameZh.js zh() 纪律）。

/** 评测场景（scenario_id → { name, desc }） */
export const SCENARIO_ZH = {
  S1: {
    name: '支付失败率 50%',
    desc: '特性开关把支付请求失败比例调到 50%，结算可用性快速烧损触发 page 告警。',
  },
  S2: {
    name: '支付服务不可达',
    desc: '特性开关模拟支付服务整体不可达，结算链路 RPC 错误率抬升触发烧损告警。',
  },
  S3: {
    name: 'F1 幂等失效',
    desc: '订单创建跳过幂等机制，同一请求被落成多张重复订单。',
  },
  S4: {
    name: 'F2 状态回跳',
    desc: '绕过状态机直接改写订单状态字段，制造回跳或无支付事实的非法状态。',
  },
  S5: {
    name: 'F3 超时结果未知',
    desc: '支付路径超时挂起，订单停在已创建、资源悬挂，等待对账收敛。',
  },
  S16: {
    name: 'F9 掉单（支付成功订单未推进）',
    desc: '支付成功后回调收口被跳过，订单不再推进；技术指标全绿，只有业务计数暴露。',
  },
  S17: {
    name: 'F10 支付悬挂（下单后支付未发起）',
    desc: '支付授权创建后故障点沉默，不发起网关请求也不报错，待支付订单悄悄积压。',
  },
  S18: {
    name: 'F11 重复扣款（支付层幂等失效）',
    desc: '支付重试跳过已支付闸，同一订单被重复扣款且多笔成功。',
  },
  S19: {
    name: 'F12 三方对账不平（数量差）',
    desc: '命中订单漏记库存扣减行，订单、支付、库存三方记账数量对不上。',
  },
  S20: {
    name: 'F13 库存超卖（预占竞态）',
    desc: '库存预占竞态下产生超额扣减，出现负库存与超卖事实。',
  },
  S21: {
    name: 'F14 下单事件丢失（履约未触发）',
    desc: '订单已创建但履约事件无痕丢失，下游履约零感知、无错误日志。',
  },
  S22: {
    name: 'F15 消息重复消费（重复履约）',
    desc: '消费端幂等缺失，同一履约单被重复消费，产生重复履约与重复通知。',
  },
  S23: {
    name: '下单成功率 KPI 下跌（多因素低剂量）',
    desc: '低比例幂等失效叠加下游小延迟，单项技术指标都不越阈，业务成功率整体下跌。',
  },
  S24: {
    name: 'F16 断流（零订单）',
    desc: '创单入口静默拒绝，订单创建计数归零；需先区分真断流与指标采集中断。',
  },
  S25: {
    name: 'F17 履约超时积压',
    desc: '履约收口停滞，履约单停在确认中超龄积压，下单链路完全正常。',
  },
  S26: {
    name: '全工具复合（F9 掉单+变更归因诱导+审批收口）',
    desc: '掉单故障叠加窗内真实发布变更，考变更相关性≠因果性的鉴别与全工具链取证；处置建议须走人工审批链，零直接执行。',
  },
}

/** 告警名（alertname → 中文名；来源 deploy/alert/prometheus/rules/*.yml） */
export const ALERTNAME_ZH = {
  checkout: '结算可用性烧损',
  ArenaDuplicateOrders: '订单重复创建',
  ArenaIllegalTransitions: '订单状态非法迁移',
  ArenaOrderStuck: '订单卡顿悬挂',
  ArenaPaymentOrderMismatch: '支付成功但订单未推进',
  ArenaPendingPaymentBacklog: '待支付订单积压',
  ArenaDuplicatePayments: '重复扣款',
  ArenaReconciliationDiff: '三方对账不平',
  ArenaOversell: '库存超卖',
  ArenaFulfillmentGap: '履约缺口（下单未触发履约）',
  ArenaDuplicateFulfillment: '重复履约',
  ArenaOrderSuccessRateLow: '下单成功率下跌',
  ArenaOrderZeroFlow: '订单断流（零订单）',
  ArenaFulfillmentSlaBreach: '履约超时积压',
  ArenaDomainProbeDown: '业务探针失联',
  CheckoutRpcClientErrorRateHigh: '结算下游调用错误率高',
  FrontendEventLoopP99High: '前端事件循环 P99 延迟高',
  FrontendHttp5xxRateElevated: '前端 5xx 错误率升高',
  FrontendHttp5xxRateHigh: '前端 5xx 错误率高',
  MemoryGateTier1: '内存闸门一级',
  MemoryGateTier2: '内存闸门二级',
  MemoryGateTier3: '内存闸门三级',
}

/** 场景中文名与描述；未命中回退 { name: id, desc: '' }（原 id 上屏，便于对账补录） */
export function scenarioZh(id) {
  return SCENARIO_ZH[id] ?? { name: id, desc: '' }
}

/** 告警中文名；未命中回退原文（绝不猜测翻译） */
export function alertZh(alertname) {
  return ALERTNAME_ZH[alertname] ?? alertname
}
