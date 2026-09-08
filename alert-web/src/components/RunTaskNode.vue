<template>
  <!-- 四重编码：底色 + 边框（实/虚/点/粗）+ 图标 + 节点内原始状态标签（英文机器码+中文名） -->
  <div class="task-node" :class="[st.cls, { dim: data.dim, sel: selected }]">
    <div class="t-name">{{ data.task.name }} <span class="t-icon">{{ st.icon }}</span></div>
    <div class="t-status">{{ data.task.status }} {{ st.zh }}</div>
  </div>
</template>

<script setup>
// P3 调查详情专用：Vue Flow 自定义任务节点（线框 v1.6 #p3 DAG 图例 11 态）
import { computed } from 'vue'
import { STATUS_STYLE } from './RunDagStatus.js'

const props = defineProps({
  data: { type: Object, required: true },
  selected: { type: Boolean, default: false },
})
const st = computed(() => STATUS_STYLE[props.data.task.status] || STATUS_STYLE.READY)
</script>

<style scoped>
.task-node {
  width: 176px; border-radius: 8px; padding: 7px 10px 6px;
  border: 1px solid var(--line-strong); background: #fff;
  font-size: 12px; line-height: 1.45; text-align: center;
  transition: opacity .15s, box-shadow .15s;
}
.t-name { font-weight: 600; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.t-icon { font-weight: 400; margin-left: 2px; }
.t-status { font-size: 10.5px; }

.task-node.dim { opacity: .28; }
.task-node.sel { box-shadow: 0 0 0 3px rgba(31, 111, 235, .28); }

/* —— 11 态图形约定（与 RunDagStatus.js 图例表一一对应）—— */
.st-ready { background: #fff; border: 1px solid #8a94a1; }
.st-ready .t-status { color: #5b6572; }

.st-leased { background: var(--brand-soft); border: 1px solid var(--brand); }
.st-leased .t-status { color: var(--brand); }

.st-running { background: #dbe7fd; border: 2px solid var(--brand); }
.st-running .t-status { color: var(--brand); }

.st-retry { background: var(--warn-bg); border: 1px solid var(--orange); }
.st-retry .t-status { color: var(--orange); }

.st-done { background: #e6f4ea; border: 1px solid var(--ok); }
.st-done .t-status { color: var(--ok); }

.st-blocked { background: #eceff1; border: 1px dashed #999; }
.st-blocked .t-name, .st-blocked .t-status { color: #666; }

.st-skipped { background: #eceff1; border: 1px solid #999; }
.st-skipped .t-name { color: #666; text-decoration: line-through; }
.st-skipped .t-status { color: #666; }

.st-cancelled { background: #eceff1; border: 1px solid #8a94a1; }
.st-cancelled .t-name, .st-cancelled .t-status { color: #666; }

.st-failed { background: var(--bad-bg); border: 1px solid var(--bad); }
.st-failed .t-status { color: var(--bad); }

.st-dead { background: #f6d5d2; border: 2px solid var(--bad); }
.st-dead .t-status { color: var(--bad); font-weight: 700; }

.st-stale {
  background: repeating-linear-gradient(45deg, #f1f3f5, #f1f3f5 6px, #e4e7eb 6px, #e4e7eb 12px);
  border: 1px dotted #8a94a1;
}
.st-stale .t-name, .st-stale .t-status { color: #666; }
</style>
