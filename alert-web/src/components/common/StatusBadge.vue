<template>
  <el-tag
    v-if="conf"
    :type="conf.type"
    :color="conf.color"
    :effect="conf.effect ?? 'light'"
    :style="conf.style"
    disable-transitions
  >{{ conf.text }}</el-tag>
</template>

<script setup>
// 状态/严重度徽章：动词化状态与 severity 五级 → el-tag 映射表（单一事实源，全站复用）
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: '' },   // Run/Case 状态码或中文
  severity: { type: String, default: '' }, // P0|P1|P2|P3|INFO
})

// 动词化标签；待审查=最高优先级中间态，用琥珀色（warning）突出；FIRING=告警中（danger）
const STATUS_MAP = {
  FIRING: { text: '告警中', type: 'danger' },
  QUEUED: { text: '排队', type: 'info' },
  PENDING: { text: '排队', type: 'info' },
  RUNNING: { text: '执行中', type: 'primary' },
  IN_PROGRESS: { text: '执行中', type: 'primary' },
  PENDING_REVIEW: { text: '待审查', type: 'warning', effect: 'dark' },
  REVIEWING: { text: '待审查', type: 'warning', effect: 'dark' },
  COMPLETED: { text: '已完成', type: 'success' },
  DONE: { text: '已完成', type: 'success' },
  RESOLVED: { text: '已解决', type: 'success' },
  FAILED: { text: '已失败', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'info' },
  CANCELED: { text: '已取消', type: 'info' },
  // Run/任务状态机（rca_run/rca_task 投影码；SUCCEEDED 落位待审查桶，与队列口径一致）
  REPORTING: { text: '报告组装中', type: 'primary' },
  SUCCEEDED: { text: '待审查', type: 'warning', effect: 'dark' },
  PARTIAL: { text: '部分完成', type: 'warning' },
  EXPIRED: { text: '已过期', type: 'info' },
  SUPERSEDED: { text: '已被取代', type: 'info' },
  READY: { text: '就绪', type: 'info' },
  LEASED: { text: '已领取', type: 'primary' },
  BLOCKED: { text: '阻塞', type: 'info' },
  RETRY_WAIT: { text: '等待重试', type: 'warning' },
  SKIPPED: { text: '已跳过', type: 'info' },
  FAILED_TERMINAL: { text: '已失败', type: 'danger' },
  DEAD: { text: '死信', type: 'danger' },
  STALE: { text: '已换代', type: 'info' },
}

// severity 五级：自定义色（Arco 第 6 级），plain 底不喧宾夺主
const SEVERITY_MAP = {
  P0: { text: 'P0', color: '#fdeceb', style: { color: 'var(--sev-p0)' } },
  P1: { text: 'P1', color: '#fff3e8', style: { color: 'var(--sev-p1)' } },
  P2: { text: 'P2', color: '#fdf6e4', style: { color: 'var(--sev-p2)' } },
  P3: { text: 'P3', color: '#e8f1ff', style: { color: 'var(--sev-p3)' } },
  INFO: { text: 'INFO', color: '#f2f4f8', style: { color: 'var(--sev-info)' } },
}

const conf = computed(() => {
  if (props.severity) {
    const s = SEVERITY_MAP[props.severity.toUpperCase()] ?? SEVERITY_MAP.INFO
    return { ...s, effect: 'light' }
  }
  if (!props.status) return null
  return STATUS_MAP[props.status.toUpperCase()] ?? { text: props.status, type: 'info' }
})
</script>

<style scoped>
.el-tag { border: none; font-weight: 600; }
</style>
