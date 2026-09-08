// src/components/RunDagStatus.js — P3 任务 DAG 11 态映射表
// 唯一事实源：线框 v1.6 #p3 图例表 + annot「DAG 图」：
// 颜色可聚类，但边框/虚线/图标/节点内原始状态标签必须区分（四重编码），
// 取消、跳过、过期、确定性失败不得混同。节点样式类由 RunTaskNode.vue 的 scoped style 实现。
export const STATUS_STYLE = {
  READY: { zh: '就绪', icon: '○', cls: 'st-ready', graphic: '白底 ○ 灰实线框', desc: '依赖已满足，待领取' },
  LEASED: { zh: '已领取', icon: '◔', cls: 'st-leased', graphic: '浅蓝底 ◔ 蓝实线框', desc: 'worker 已领，未开始' },
  RUNNING: { zh: '运行中', icon: '▶', cls: 'st-running', graphic: '蓝底 ▶ 蓝粗框', desc: '执行中' },
  RETRY_WAIT: { zh: '等待重试', icon: '↻', cls: 'st-retry', graphic: '黄底 ↻ 橙实线框', desc: '可重试失败，等退避' },
  DONE: { zh: '完成', icon: '✓', cls: 'st-done', graphic: '绿底 ✓ 绿实线框', desc: '成功终态' },
  BLOCKED: { zh: '阻塞', icon: '⊘', cls: 'st-blocked', graphic: '灰底 ⊘ 灰虚线框', desc: '依赖未完成' },
  SKIPPED: { zh: '已跳过', icon: '⇥', cls: 'st-skipped', graphic: '灰底 ⇥ 灰框+删除线', desc: '上游失败跳过' },
  CANCELLED: { zh: '已取消', icon: '✕', cls: 'st-cancelled', graphic: '灰底 ✕ 灰框', desc: '人工/系统取消' },
  FAILED_TERMINAL: { zh: '确定性失败', icon: '✗', cls: 'st-failed', graphic: '红底 ✗ 红实线框', desc: '不可重试失败' },
  DEAD: { zh: '死信', icon: '✗✗', cls: 'st-dead', graphic: '深红底 ✗✗ 红粗框', desc: '重试耗尽' },
  STALE: { zh: '过期', icon: '⌛', cls: 'st-stale', graphic: '灰斜纹底 ⌛ 灰点线框', desc: 'generation 已换代' },
}

// 「仅看异常」筛选集：非健康/非终态成功之外需要人工关注的状态
export const ABNORMAL_STATUS = new Set(['RETRY_WAIT', 'BLOCKED', 'FAILED_TERMINAL', 'DEAD', 'STALE'])

export const STATUS_ORDER = Object.keys(STATUS_STYLE)
