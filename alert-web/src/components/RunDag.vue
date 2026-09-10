<template>
  <div class="run-dag">
    <VueFlow
      class="dag-canvas"
      :nodes="vfNodes"
      :edges="vfEdges"
      :nodes-draggable="true"
      :nodes-connectable="false"
      :edges-updatable="false"
      fit-view-on-init
      :min-zoom="0.3"
      :max-zoom="1.6"
    >
      <template #node-task="nodeProps">
        <RunTaskNode v-bind="nodeProps" />
      </template>
    </VueFlow>
  </div>
</template>

<script setup>
// P3 调查详情专用：任务 DAG（annot「DAG 图」选型：Vue Flow + dagre 自动布局，E-18 v1.1）
// 节点=任务，边=依赖（rca_task + rca_task_edge 只读投影）；SSE 事件驱动节点实时变色
import { computed, watch, nextTick } from 'vue'
import { VueFlow, useVueFlow, MarkerType } from '@vue-flow/core'
import dagre from '@dagrejs/dagre'
import RunTaskNode from './RunTaskNode.vue'
import { ABNORMAL_STATUS } from './RunDagStatus.js'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

const props = defineProps({
  tasks: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  selectedId: { type: String, default: null },
  abnormalOnly: { type: Boolean, default: false },
  neighborFocus: { type: Boolean, default: false },
})
const emit = defineEmits(['select'])

const { onNodeClick, fitView } = useVueFlow()
onNodeClick(({ node }) => emit('select', node.id))

const NODE_W = 176
const NODE_H = 58

// 「仅看异常」：只保留异常态节点及其内部边，并重新布局
const visible = computed(() => {
  const tasks = props.abnormalOnly ? props.tasks.filter(t => ABNORMAL_STATUS.has(t.status)) : props.tasks
  const ids = new Set(tasks.map(t => t.id))
  return { tasks, edges: props.edges.filter(e => ids.has(e.source) && ids.has(e.target)) }
})

// 「上游/下游」聚焦：非选中节点直接邻居的节点调暗（数据保留，只调视觉权重）
function isDim(id) {
  if (!props.neighborFocus || !props.selectedId || id === props.selectedId) return false
  return !props.edges.some(e =>
    (e.source === props.selectedId && e.target === id) ||
    (e.target === props.selectedId && e.source === id))
}

// dagre 自动布局（rankdir=TB），输出 Vue Flow 节点坐标
function layout(tasks, edges) {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 48, ranksep: 64, marginx: 12, marginy: 12 })
  tasks.forEach(t => g.setNode(t.id, { width: NODE_W, height: NODE_H }))
  edges.forEach(e => g.setEdge(e.source, e.target))
  dagre.layout(g)
  return tasks.map(t => {
    const p = g.node(t.id)
    return {
      id: t.id,
      type: 'task',
      position: { x: p.x - NODE_W / 2, y: p.y - NODE_H / 2 },
      data: { task: t, dim: isDim(t.id) },
    }
  })
}

const vfNodes = computed(() => layout(visible.value.tasks, visible.value.edges))
const vfEdges = computed(() => visible.value.edges.map(e => ({
  id: `${e.source}->${e.target}`,
  source: e.source,
  target: e.target,
  markerEnd: { type: MarkerType.ArrowClosed, color: '#8a94a1' },
  style: { stroke: '#8a94a1', strokeWidth: 1.5 },
})))

watch(() => props.abnormalOnly, () => nextTick(() => fitView({ padding: 0.2 })))

defineExpose({ fit: () => fitView({ padding: 0.2 }) })
</script>

<style scoped>
.run-dag { border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; background: #fbfcfe; }
.dag-canvas { height: 430px; }
/* 覆盖 vue-flow 默认节点包装样式，让自定义节点完全接管视觉 */
:deep(.vue-flow__node-task) { border: none; background: transparent; padding: 0; box-shadow: none; }
:deep(.vue-flow__node-task.selectable:focus) { outline: none; box-shadow: none; }
</style>
